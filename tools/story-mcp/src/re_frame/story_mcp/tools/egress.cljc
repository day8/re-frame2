(ns re-frame.story-mcp.tools.egress
  "Wire-egress scrubbers for the MCP tool handlers (rf2-73wuj, split out
  of the former `tools.helpers` in rf2-8yvyp).

  Per spec/Tool-Pair.md §Direct-read privacy posture (lines 544-566):
  every pair-shaped tool surfacing live frame state MUST route the
  value through `re-frame.core/elide-wire-value` (or the value-based
  derived-tree redaction below) before the value crosses the wire
  egress.

  In story-mcp the two surfaces that ship live-state reads are
  `preview-variant` / `run-variant` (which return the variant frame's
  `:app-db` slice) and `read-failures` (which returns the variant
  frame's `:rf.story/assertions` accumulator). The walker reads the
  live schema-owned `[:rf/runtime :elision]` registries from the named
  frame's app-db; the `:frame variant-id` opts slot is load-bearing.

  ## Path-based redaction (`elide-app-db`, `scrub-assertions`)

  Apply the cross-MCP privacy-posture rules to every live `:app-db`
  slice and assertion accumulator before egress. Off-box defaults
  (schema-declared sensitive paths return `:rf/redacted`; assertion
  records stamped `:sensitive? true` are dropped). The shared
  `:include-sensitive` arg is the documented opt-in escape hatch.

  ## Derived-tree value-based redaction (rf2-ee38b.17, `scrub-rendered`)

  `elide-app-db` closes the leak for the `:app-db` slot — but the same
  sensitive value reappears, VERBATIM, in `:rendered-hiccup` (the variant
  view renders `[:input {:value <token>}]`), in `:effective-args` (the
  resolved arg map that fed the render), and in any `:snapshot` body.
  Those are derived from the same app-db, but they are NOT keyed by
  app-db path — the token sits at a hiccup-tree position
  (`[1 :value]`), not at `[:user :token]`. `elide-wire-value` matches
  the schema-declared SENSITIVE PATHS, so running it over a hiccup
  tree finds nothing: the path-based walker is structurally blind to
  the re-keyed copy.

  The sound posture for a DERIVED tree is VALUE-based redaction:
  collect the live values sitting at the frame's declared-`:sensitive?`
  app-db paths, then substitute any leaf in the derived tree that
  EQUALS one of them with the same `:rf/redacted` sentinel
  `elide-wire-value` emits. This honours the Tool-Pair §Direct-read
  privacy MUST intent — 'live runtime state crossing the MCP egress
  is scrubbed' — for the rendered surface, with the same
  `:include-sensitive` opt-out escape hatch as `:app-db`."
  (:require [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.mcp-base.sensitive :as sensitive]))

;; ---------------------------------------------------------------------------
;; Path-based redaction
;; ---------------------------------------------------------------------------

(defn- refresh-elision-from-schemas!
  "Refresh schema-owned elision declarations before a direct wire read.
  Event dispatch does this in the router, but MCP tools can read a frame
  after non-event setup paths too."
  [variant-id]
  (when-let [populate! (late-bind/get-fn-cached :elision/populate-from-schemas!)]
    (try
      (populate! variant-id)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn elide-app-db
  "Run `app-db` through `re-frame.core/elide-wire-value` against
  `variant-id`'s frame registry. Returns the elided value, or the input
  unchanged when `include?` is true.

  Two short-circuits avoid pointless work:

    - Nil-safe — a nil `app-db` returns immediately (the walker treats
      nil as a non-elidable scalar, but we pre-check to avoid the
      registry lookup on the empty-frame happy path).

    - `include? true` returns the input unchanged. With both inclusion
      knobs flipped on the walker yields `v` at every node (per
      `elide-wire-value`'s composition rule: `sensitive?` and `large?`
      both return `v` when their inclusion flag is true; no marker
      emit, no schema-driven elision, no warning). The walk is a pure
      no-op — full traversal, zero edits — so we skip it. The escape
      hatch should be free."
  [app-db variant-id include?]
  (cond
    (nil? app-db) app-db
    include?      app-db
    :else         (do
                    (refresh-elision-from-schemas! variant-id)
                    (rf/elide-wire-value app-db {:frame variant-id}))))

(defn scrub-assertions
  "Default-drop any assertion records carrying the top-level
  `:sensitive? true` stamp. Reuses `strip-sensitive` (the shared trace-
  event filter from `mcp-base.sensitive`) — assertion records and trace
  events both honour the same convention, so a single primitive covers
  both surfaces.

  Two short-circuits avoid pointless work on the opt-in / empty paths:

    - `include? true` returns `(vec (or records []))` directly — the
      walker would yield the input unchanged anyway (no drops with the
      escape hatch open), so we skip the traversal.
    - `nil`/empty records short-return `[]`.

  Returns the kept-vec (the `dropped-count` second slot is suppressed
  here; the caller is the wire egress, not an audit surface)."
  [records include?]
  (cond
    include?       (vec (or records []))
    (nil? records) []
    :else          (first (sensitive/strip-sensitive records false))))

;; ---------------------------------------------------------------------------
;; Derived-tree value-based redaction (rf2-ee38b.17)
;; ---------------------------------------------------------------------------

(defn- sensitive-values
  "The set of live values sitting at `variant-id`'s declared-`:sensitive?`
  app-db paths, read out of `app-db`. Used to value-redact derived trees
  (rendered hiccup, effective-args, snapshot) where the same value
  reappears at a non-app-db path the path-based walker can't reach.

  Refreshes the schema-owned declarations first (the elision registry is
  populated from `{:sensitive? true}` schema metadata, same as
  `elide-app-db`'s pre-step). Nil / boolean values are excluded — a `nil`
  or `false` leaf is not a secret and value-matching them would scrub
  swathes of benign tree."
  [app-db variant-id]
  (refresh-elision-from-schemas! variant-id)
  (let [decls (rf/elision-sensitive-declarations variant-id)]
    (into #{}
          (comp (map (fn [path] (get-in app-db (vec path) ::absent)))
                (remove #(or (= ::absent %) (nil? %) (boolean? %))))
          (keys decls))))

(defn- redact-matching
  "Walk `tree`, substituting any leaf `=` to a member of `secrets` with the
  `:rf/redacted` sentinel. Recurses through maps, vectors, sets, seqs;
  treats every other value as a leaf. Map KEYS are walked too — a secret
  used as a key (rare, but a `{:value <token>}`-style attribute map could
  in principle key on one) is redacted on both sides."
  [tree secrets]
  (cond
    (contains? secrets tree) :rf/redacted
    (map? tree)    (persistent!
                     (reduce-kv (fn [acc k v]
                                  (assoc! acc
                                          (redact-matching k secrets)
                                          (redact-matching v secrets)))
                                (transient {})
                                tree))
    (vector? tree) (mapv #(redact-matching % secrets) tree)
    (set? tree)    (into #{} (map #(redact-matching % secrets)) tree)
    (seq? tree)    (map #(redact-matching % secrets) tree)
    :else          tree))

(defn scrub-rendered
  "Value-redact a DERIVED tree (rendered hiccup, `:effective-args`, a
  snapshot body) before wire egress. The path-based `elide-wire-value`
  walker scrubs `:app-db` by path, but the same sensitive value reappears
  in these derived surfaces at a non-app-db position — so we collect the
  live values at `variant-id`'s declared-`:sensitive?` paths and substitute
  any matching leaf in `tree` with `:rf/redacted` (rf2-ee38b.17).

  Short-circuits, mirroring `elide-app-db`:

    - `include? true` returns `tree` unchanged (the opt-out escape hatch).
    - A nil `tree` or nil `app-db` returns `tree` (nothing to scrub /
      no source of secrets).
    - No declared-sensitive values ⇒ `tree` is returned unwalked (the
      common case is one cheap set build, then the no-secrets early out)."
  [tree app-db variant-id include?]
  (cond
    include?        tree
    (nil? tree)     tree
    (nil? app-db)   tree
    :else           (let [secrets (sensitive-values app-db variant-id)]
                      (if (empty? secrets)
                        tree
                        (redact-matching tree secrets)))))
