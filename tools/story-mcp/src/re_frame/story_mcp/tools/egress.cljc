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

  ## Non-live runtime/captured value scrub (`scrub-frame-value`, rf2-12f2q)

  The wire-elision contract (`tools/story/spec/006-MCP-Surface.md`)
  promises EVERY Story-MCP payload crosses elided — not just the three
  live-state tools'. The NON-live tools also cross runtime/captured
  VALUES that can sit at a frame's declared-`:sensitive?` paths:
  `explain-variant`'s plan-RESOLVED value slots (`:effective-args` /
  `:args` / `:substitutions` / `:network` / `:db-seed`) and
  `record-as-variant`'s `:captured` event vectors (+ the `:play-snippet`
  rendered from them). `scrub-frame-value` gives those payloads the SAME
  value-based redaction `scrub-rendered` applies to live derived trees,
  keyed to the variant frame — it reads the frame's app-db itself rather
  than receiving it. Author-published STATIC registration metadata
  (story/variant bodies, registry enumerations, the explain plan-
  STRUCTURE slots) is intentionally public and NOT scrubbed; see
  `scrub-frame-value` for the runtime-vs-authored split.

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
  guards that (rf2-g7cd1, hardened rf2-f3kf7) by dropping any candidate
  that ALSO appears, VERBATIM, in the POST-elision `:app-db` (the actual
  wire bytes) — such a value is already disclosed by the path-based
  `:app-db` egress, so excluding it leaks nothing new while restoring the
  benign leaves. Classifying against the elided db (not the raw db) is
  load-bearing: a secret aliased into a `:large?`-declared subtree is
  replaced by the `:rf.size/large-elided` marker on the wire, so it is NOT
  disclosed and MUST stay redacted. See `sensitive-values` for the
  fail-SAFE argument."
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
  "True when `path` is `prefix` or descends from it (element-wise prefix
  match). Both are indexed vectors. Used to decide whether an app-db
  position is governed by a declared-`:sensitive?` path — a slot marked
  sensitive covers itself AND everything beneath it."
  [prefix path]
  (let [pn (count prefix)]
    (and (<= pn (count path))
         (loop [i 0]
           (cond
             (== i pn)                       true
             (= (nth prefix i) (nth path i)) (recur (inc i))
             :else                           false)))))

(defn- collect-governed-values!
  "Walk the RAW `node` at `path`, conj!ing onto transient set `acc!` every
  scalar value sitting at (or beneath) a `:sensitive?` prefix — the
  candidate secrets. Returns `acc!`.

  Indexing MIRRORS `elide-wire-value`'s walk so a declared path lands on
  the SAME node the elider redacts: maps descend by key, vectors AND seqs
  descend by integer index (the fix for the seq-indexed `[:tokens 0]`
  facet — `get-in` cannot read a seq index, so the old candidate-by-`get-in`
  extraction silently dropped seq-indexed secrets => under-scrub), and
  sets are walked at their own path (set elements have no stable index, so
  a whole sensitive-declared set contributes all its members).

  Only governed positions contribute, and `nil` / boolean leaves are
  skipped (a `nil`/`false` is not a secret and value-matching it would
  scrub swathes of benign tree). Collections that ARE governed still
  recurse so nested scalars under a sensitive subtree are all collected."
  [acc! node path sensitive-prefixes]
  (let [governed? (some #(under-prefix? % path) sensitive-prefixes)]
    (when (and governed?
               (not (coll? node))
               (not (nil? node))
               (not (boolean? node)))
      (conj! acc! node))
    (cond
      (map? node)
      (reduce-kv (fn [a k v]
                   (collect-governed-values! a v (conj path k) sensitive-prefixes))
                 acc! node)

      (vector? node)
      (reduce (fn [a i] (collect-governed-values! a (nth node i) (conj path i)
                                                  sensitive-prefixes))
              acc! (range (count node)))

      (seq? node)
      (let [idx (volatile! -1)]
        (reduce (fn [a x]
                  (collect-governed-values! a x (conj path (vswap! idx inc))
                                            sensitive-prefixes))
                acc! node))

      (set? node)
      ;; Sets have no stable element index; walk members at the set's own
      ;; path so a sensitive-declared set contributes every member.
      (reduce (fn [a x] (collect-governed-values! a x path sensitive-prefixes))
              acc! node)

      :else
      acc!)))

(defn- collect-wire-values!
  "Walk the POST-elision `node`, conj!ing onto transient set `acc!` every
  value (intermediate collections AND leaves) that is `=` to a candidate
  secret. `node` is the elided `:app-db` — the actual wire bytes — so any
  candidate found here is one the path-based `:app-db` egress ships
  VERBATIM. Returns `acc!`.

  Because the input is already elided, every `:sensitive?` slot is the
  `:rf/redacted` sentinel and every `:large?` (or auto-detected) slot is
  the `:rf.size/large-elided` marker — the secret value simply is not
  present at any governed position, so there is no path-shape reasoning to
  get right (no prefix walk, no seq-index correction). Membership is the
  only question; map keys, vector elements, and set/seq elements are all
  walked, since a candidate aliased to ANY surviving wire position is
  disclosed."
  [acc! node candidates]
  (when (contains? candidates node)
    (conj! acc! node))
  (cond
    (map? node)
    (reduce-kv (fn [a k v]
                 (collect-wire-values! a k candidates)
                 (collect-wire-values! a v candidates))
               acc! node)

    (coll? node)
    (reduce (fn [a x] (collect-wire-values! a x candidates))
            acc! node)

    :else
    acc!))

(defn- sensitive-values
  "The set of live values sitting at `variant-id`'s declared-`:sensitive?`
  app-db paths, read out of the RAW `app-db`. Used to value-redact derived
  trees (rendered hiccup, effective-args, snapshot) where the same value
  reappears at a non-app-db path the path-based walker can't reach.

  Refreshes the schema-owned declarations first (the elision registry is
  populated from `{:sensitive? true}` schema metadata, same as
  `elide-app-db`'s pre-step). Nil / boolean values are excluded — a `nil`
  or `false` leaf is not a secret and value-matching them would scrub
  swathes of benign tree.

  ## Non-unique-secret guard (rf2-g7cd1, hardened rf2-f3kf7)

  Value-based redaction is a heuristic: it substitutes EVERY derived-tree
  leaf `=` a sensitive value. When a sensitive path holds a short/common
  scalar (`0`, an HTTP `200`, `:ok`, `\"\"`), naive value-matching scrubs
  every benign leaf that merely happens to equal it — degrading the
  agent's view AND leaking the secret's value-CLASS.

  The guard subtracts any candidate value that ALSO appears, VERBATIM, on
  the wire — i.e. in the POST-elision `:app-db` (`elide-app-db` of the raw
  db). Such a value is provably NOT a protected secret: the path-based
  `:app-db` egress already discloses it, so removing it from the
  derived-tree secret set leaks nothing new — the fail-SAFE invariant
  (never leak a genuine secret) holds by construction.

  Classifying against the elided db (rather than the raw db) is the
  correction for rf2-f3kf7: the original guard walked the RAW db, so a
  secret that ALSO lived under a `:large?`-declared non-sensitive path was
  seen at that position and dropped from the secret set — yet
  `elide-app-db` replaces a `:large?` slot with the `:rf.size/large-elided`
  marker, so the secret was NOT on the wire and then leaked VERBATIM into
  the derived trees. Reasoning against the actual wire bytes closes that
  hole AND is robust to any future elision class (digests, new markers,
  the string auto-detect threshold), not just `:large?` — and it subsumes
  the seq-indexed `:sensitive?` edge (an indexed element redacted by
  `elide-wire-value` simply does not appear in the elided db).

  A value that survives ONLY under sensitive / large positions (so is
  absent from the elided db) stays in the set and is redacted everywhere
  (no under-scrub); the irreducible same-value aliasing case (a
  uniquely-secret short scalar) still over-scrubs — that residual is
  fail-SAFE, never under-safe."
  [app-db variant-id]
  (refresh-elision-from-schemas! variant-id)
  (let [decls              (rf/elision-sensitive-declarations variant-id)
        sensitive-prefixes (mapv vec (keys decls))
        ;; Collect candidate secrets by WALKING the raw db at governed
        ;; positions (mirroring the elider's indexing) rather than reading
        ;; each declared path with `get-in` — `get-in` cannot index into a
        ;; seq, so seq-indexed declarations (`[:tokens 0]`) would otherwise
        ;; silently yield no candidate and the secret would leak.
        candidates         (persistent!
                             (collect-governed-values! (transient #{}) app-db []
                                                        sensitive-prefixes))]
    (if (empty? candidates)
      #{}
      ;; Classify "public" against the actual wire bytes: the elided
      ;; app-db. A candidate present here is shipped verbatim by the
      ;; :app-db egress (already disclosed) so it is dropped; one that is
      ;; absent (redacted / elided away) stays redacted in derived trees.
      (let [wire   (rf/elide-wire-value app-db {:frame variant-id})
            public (persistent!
                     (collect-wire-values! (transient #{}) wire candidates))]
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
;; Non-live runtime/captured value scrub (rf2-12f2q)
;; ---------------------------------------------------------------------------
;;
;; The three live-state tools (`preview-variant` / `run-variant` /
;; `read-failures`) hold the post-run `:app-db` in hand and feed it to
;; `elide-app-db` (path) + `scrub-rendered` (value). But the NON-live
;; tools — `explain-variant`'s plan-resolved value slots and
;; `record-as-variant`'s captured event vectors — also cross the
;; AI/off-box boundary carrying runtime/captured VALUES that can sit at a
;; frame's declared-`:sensitive?` paths: a `:network` route reply seeded
;; with a real token, an `:effective-args` slot resolved from a sensitive
;; arg, a captured event dispatched with a secret payload.
;;
;; Those tools did NOT route their value-bearing slots through any
;; scrubber until rf2-12f2q, so the wire-elision contract in
;; `tools/story/spec/006-MCP-Surface.md` ("every Story-MCP payload crosses
;; elided; nothing raw") was a promise the implementation only kept for
;; the three live-state tools. The split is closed here by giving the
;; non-live tools the SAME value-based redaction — keyed to the variant
;; frame's declared-sensitive values — that `scrub-rendered` already
;; applies to the live derived trees. The slot is value-bearing and
;; frame-keyed (the recorder records against `vk`; the plan is compiled
;; for `vk`), so the same `sensitive-values vk` candidate set governs it.
;;
;; INTENTIONALLY-PUBLIC (NOT scrubbed): the docs-discovery surfaces that
;; return author-published STATIC registration prose — `get-story` /
;; `get-variant` / `variant->edn` bodies, `list-stories` / `list-modes` /
;; `list-decorators` / `list-tags` / `list-assertions` enumerations, the
;; markdown render, and the `explain` map's plan-STRUCTURE slots
;; (`:source-chain` / `:parent-chain` / `:compose` / `:merge` /
;; `:strict-conflicts` / `:setup-order` / `:script-order` / `:tags` /
;; `:platforms` / …). Those are the catalogue an author publishes for
;; discovery — not runtime/user state — and the threat model
;; (`spec/015-Data-Classification.md`) scopes the marks to the OBSERVED
;; runtime, not authored registration data. Registry-wide enumerations
;; (modes, decorators) are not frame-keyed and carry no runtime values;
;; their `:args` / `:app-db-patch` / `:response` slots are the author's
;; own published fixture data. See `tools/story/spec/006-MCP-Surface.md`
;; §Wire-elision boundary for the single-sourced classification.

(defn scrub-frame-value
  "Value-redact a non-live, value-bearing payload `tree` that is keyed to
  variant `variant-id`'s frame, before wire egress (rf2-12f2q). Reads the
  frame's live `:app-db` itself (via `re-frame.core/app-db-value`) — the
  non-live handlers do not already hold it — collects the values sitting
  at the frame's declared-`:sensitive?` paths, and substitutes any
  matching leaf in `tree` with `:rf/redacted`.

  Thin wrapper over `scrub-rendered`: it shares the exact same VALUE-based
  redaction + the non-unique-secret guard, so a secret leaks identically
  (i.e. not at all, by default) whether it reaches the wire via a live
  derived tree or a plan-resolved / captured slot. The only difference is
  that this reads the source app-db rather than receiving it — when the
  frame has not been allocated (`app-db-value` ⇒ nil) there are no
  declared-sensitive values to collect, so the payload passes through
  unwalked.

  `include?` is the same `--allow-sensitive-reads` + per-call
  `:include-sensitive` opt-out the live tools honour — when true the raw
  value crosses (the operator signed off on the egress posture)."
  [tree variant-id include?]
  (cond
    include?    tree
    (nil? tree) tree
    :else       (scrub-rendered tree (rf/app-db-value variant-id) variant-id include?)))

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
