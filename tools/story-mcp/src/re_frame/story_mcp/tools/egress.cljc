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
  `:include-sensitive` opt-out escape hatch as `:app-db`.

  Value-matching is a heuristic; its one collateral hazard is a
  sensitive path holding a SHORT/COMMON scalar (`0`, `200`, `:ok`),
  which would scrub every benign leaf that equals it. `sensitive-values`
  guards that (rf2-g7cd1) by dropping any candidate that ALSO appears at
  a NON-sensitive app-db path — such a value is already disclosed
  verbatim by the path-based `:app-db` egress, so excluding it leaks
  nothing new while restoring the benign leaves. See `sensitive-values`
  for the fail-SAFE argument."
  (:require [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.mcp-base.elision :as base-elision]
            [re-frame.mcp-base.envelope :as base-envelope]
            [re-frame.mcp-base.sensitive :as sensitive]
            [re-frame.story-mcp.tools.result :as result]))

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

(defn scrub-assertions+count
  "Default-drop any assertion records carrying the top-level
  `:sensitive? true` stamp. Reuses `strip-sensitive` (the shared trace-
  event filter from `mcp-base.sensitive`) — assertion records and trace
  events both honour the same convention, so a single primitive covers
  both surfaces.

  Returns `[kept dropped-count]` — the kept vec PLUS the number of
  sensitive records dropped. The count is the `:dropped-sensitive`
  indicator the caller threads onto its response envelope via
  `with-indicators` (Conventions §Cross-MCP indicator-field vocabulary,
  MUST-level): an agent that sees redacted leaves but no scalar summary
  cannot tell HOW MUCH the egress filtered. This is the canonical
  silent-swallow failure mode the indicator count closes (rf2-koq5m).

  Two short-circuits avoid pointless work on the opt-in / empty paths:

    - `include? true` returns `[(vec (or records [])) 0]` directly — the
      walker would yield the input unchanged anyway (no drops with the
      escape hatch open), so we skip the traversal.
    - `nil`/empty records short-return `[[] 0]`."
  [records include?]
  (cond
    include?       [(vec (or records [])) 0]
    (nil? records) [[] 0]
    :else          (sensitive/strip-sensitive records false)))

(defn scrub-assertions
  "Kept-vec-only projection of `scrub-assertions+count` — the historical
  signature for call sites that don't surface the dropped count. New
  egress paths SHOULD prefer `scrub-assertions+count` and thread the
  count onto the envelope via `with-indicators` (rf2-koq5m)."
  [records include?]
  (first (scrub-assertions+count records include?)))

;; ---------------------------------------------------------------------------
;; Derived-tree value-based redaction (rf2-ee38b.17)
;; ---------------------------------------------------------------------------

(defn- under-prefix?
  "True when `path` is `prefix` or descends from it (element-wise
  prefix match). Both are indexed vectors. Used to decide whether an
  app-db position is governed by a declared-`:sensitive?` path — a slot
  marked sensitive covers itself AND everything beneath it."
  [prefix path]
  (let [pn (count prefix)]
    (and (<= pn (count path))
         (loop [i 0]
           (cond
             (== i pn)                       true
             (= (nth prefix i) (nth path i)) (recur (inc i))
             :else                           false)))))

(defn- collect-public-values!
  "Walk `node` at `path`, conj!ing onto transient set `acc!` every value
  (intermediate collections AND leaves) that (a) is `=` to a candidate
  secret and (b) sits at a position NOT governed by any sensitive
  prefix. A value reached only under a sensitive prefix is never
  collected, so it cannot dilute the candidate set. Returns `acc!`.

  Map keys are walked at the same `path` as their entry (a key is not a
  distinct app-db segment), so a candidate used as a benign key at a
  non-sensitive position also counts as public."
  [acc! node path sensitive-prefixes candidates]
  (let [governed? (some #(under-prefix? % path) sensitive-prefixes)]
    (when (and (not governed?) (contains? candidates node))
      (conj! acc! node))
    (cond
      (map? node)
      (reduce-kv (fn [a k v]
                   (collect-public-values! a k path sensitive-prefixes candidates)
                   (collect-public-values! a v (conj path k) sensitive-prefixes candidates))
                 acc! node)

      (vector? node)
      (reduce (fn [a i] (collect-public-values! a (nth node i) (conj path i)
                                                sensitive-prefixes candidates))
              acc! (range (count node)))

      (or (set? node) (seq? node))
      ;; Sets / seqs have no stable path segment for elements, so we walk
      ;; them at the SAME path (the collection's own position). Membership
      ;; is what matters for value-aliasing, not the index.
      (reduce (fn [a x] (collect-public-values! a x path sensitive-prefixes candidates))
              acc! node)

      :else
      acc!)))

(defn- sensitive-values
  "The set of live values sitting at `variant-id`'s declared-`:sensitive?`
  app-db paths, read out of `app-db`. Used to value-redact derived trees
  (rendered hiccup, effective-args, snapshot) where the same value
  reappears at a non-app-db path the path-based walker can't reach.

  Refreshes the schema-owned declarations first (the elision registry is
  populated from `{:sensitive? true}` schema metadata, same as
  `elide-app-db`'s pre-step). Nil / boolean values are excluded — a `nil`
  or `false` leaf is not a secret and value-matching them would scrub
  swathes of benign tree.

  ## Non-unique-secret guard (rf2-g7cd1)

  Value-based redaction is a heuristic: it substitutes EVERY derived-tree
  leaf `=` a sensitive value. When a sensitive path holds a short/common
  scalar (`0`, an HTTP `200`, `:ok`, `\"\"`), naive value-matching scrubs
  every benign leaf that merely happens to equal it — degrading the
  agent's view AND leaking the secret's value-CLASS.

  The guard subtracts any candidate value that ALSO appears at a
  NON-sensitive app-db position. Such a value is provably NOT a protected
  secret: `elide-app-db` (path-based) ships it VERBATIM in the `:app-db`
  slot via that non-sensitive path, so it is already disclosed on the
  wire. Removing it from the derived-tree secret set therefore leaks
  nothing new — the fail-SAFE invariant (never leak a genuine secret)
  holds by construction. A value that appears ONLY under sensitive paths
  stays in the set and is still redacted everywhere (no under-scrub); the
  irreducible same-value aliasing case (a uniquely-secret short scalar)
  still over-scrubs — that residual is fail-SAFE, never under-safe."
  [app-db variant-id]
  (refresh-elision-from-schemas! variant-id)
  (let [decls              (rf/elision-sensitive-declarations variant-id)
        sensitive-prefixes (mapv vec (keys decls))
        candidates         (into #{}
                                 (comp (map (fn [path] (get-in app-db path ::absent)))
                                       (remove #(or (= ::absent %) (nil? %) (boolean? %))))
                                 sensitive-prefixes)]
    (if (empty? candidates)
      #{}
      (let [public (persistent!
                     (collect-public-values! (transient #{}) app-db []
                                              sensitive-prefixes candidates))]
        ;; Keep only candidates that appear EXCLUSIVELY under sensitive
        ;; paths — those are the genuinely-secret values.
        (into #{} (remove public) candidates)))))

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

;; ---------------------------------------------------------------------------
;; Wire-egress indicator counts (rf2-koq5m).
;;
;; story-mcp's egress drops `:sensitive? true` assertion records and
;; replaces over-threshold / schema-`:large?` leaves with the
;; `:rf.size/large-elided` marker — but until rf2-koq5m it surfaced
;; NEITHER count. spec/Conventions.md §Cross-MCP indicator-field
;; vocabulary is MUST-level: a tool that walks a tree-typed payload MUST
;; carry an `:elided-large` count alongside the `:dropped-sensitive`
;; count, omitting each slot when zero. The sibling pair-mcp already
;; wires `re-frame.mcp-base.envelope/with-indicators` +
;; `re-frame.mcp-base.elision/count-elided-markers` across its tools;
;; story-mcp now reuses the SAME mcp-base primitives so the omit-when-
;; zero rule lives in one place and the count bytes stay byte-identical
;; across the pair.
;; ---------------------------------------------------------------------------

(defn count-elided
  "Count the `{:rf.size/large-elided ...}` markers `elide-app-db` /
  `scrub-rendered` left in `payload`, via the shared mcp-base walker.
  This is the `:elided-large` indicator the caller threads onto its
  response envelope. Walk the FINAL payload (post-elision) so every
  elided slot — `:app-db`, `:rendered-hiccup`, `:snapshot`, the evidence
  trees — contributes; the marker is the same shape regardless of which
  slot produced it.

  Returns an integer >= 0; cheap on the common path (no markers => one
  walk producing zero)."
  [payload]
  (base-elision/count-elided-markers payload))

(defn with-indicators
  "Splice the cross-MCP indicator-field slots (`:dropped-sensitive`,
  `:elided-large`) onto a tool's payload map, honouring the MUST-level
  omit-when-zero rule (Conventions §Cross-MCP indicator-field
  vocabulary; Spec 009 §Indicator field on tool responses).

  Thin pass-through to `re-frame.mcp-base.envelope/with-indicators` —
  the rule body lives in mcp-base so both servers in the pair re-export
  the same emit-path (the conformance gate pins the single source). The
  `counts` map is `{:dropped <n> :elided <n>}`; a zero / nil count omits
  its slot, so a clean read returns the payload unchanged."
  [payload counts]
  (base-envelope/with-indicators payload counts))

(defn result-with-indicators
  "Build the final `edn-result` for a live-state read, splicing on the
  MUST-level egress indicator counts (rf2-koq5m). The
  `:dropped`-sensitive count is supplied by the caller (from
  `scrub-assertions+count`); the `:elided`-large count is derived here
  by walking the FINAL payload for `:rf.size/large-elided` markers via
  `count-elided`.

  This is the dual-coded epilogue the three live-state handlers shared
  verbatim — `preview-variant` / `run-variant` / `read-failures` each
  closed with `(result/edn-result (with-indicators payload {:dropped d
  :elided (count-elided payload)}))`. Named once so each handler reads
  as 'return this payload with its egress indicators' rather than
  re-spelling the count-derive-and-splice dance. Counts omit their slot
  when zero (Conventions §Cross-MCP indicator-field vocabulary), so a
  clean read returns the bare payload."
  [payload dropped]
  (result/edn-result
    (with-indicators payload
                     {:dropped dropped
                      :elided  (count-elided payload)})))
