(ns re-frame.story-mcp.tools.egress
  "Wire-egress scrubbers for the MCP tool handlers (rf2-73wuj, split out
  of the former `tools.helpers` in rf2-8yvyp).

  Per spec/Tool-Pair.md §Direct-read privacy posture (lines 544-566):
  every pair-shaped tool surfacing live frame state MUST route the
  value through `re-frame.core/elide-wire-value` (or the PATH-BASED
  derived-tree projection below) before the value crosses the wire
  egress.

  ## EP-0025 fail-open posture (value-match removed)

  EP-0025 REMOVED value-match (taint-by-equality) redaction of re-keyed
  copies (§\"What is removed\": value-match is propagation/taint by another
  name, which a hygiene helper does not earn). Both the path-based `:app-db`
  egress AND the derived-tree projection are now PATH-BASED ONLY. A value at
  a CLASSIFIED app-db path redacts in the `:app-db` slice AND in any derived
  slot WHERE the value still occupies that path (a slot whose shape mirrors
  the app-db, e.g. an `:effective-args {:token …}` slice with `[:token]`
  classified, or a `:db-seed` that mirrors app-db). A value RE-KEYED to a
  position the classification path cannot reach (a token copied into rendered
  hiccup at `[1 :value]`, into a `:network` reply, into a captured-event
  payload) is NOT covered and ships RAW — INTENDED FAIL-OPEN (hygiene, not a
  guarantee). A consumer that needs a value redacted in a derived tree must
  classify its app-db PATH so the value lands AT that path.

  In story-mcp the two surfaces that ship live-state reads are
  `preview-variant` / `run-variant` (which return the variant frame's
  `:app-db` slice) and `read-failures` (which returns the variant
  frame's `:rf.story/assertions` accumulator). The walker reads the
  per-frame `[:rf.runtime/elision]` registries (EP-0025 — durable
  classification declared via the commit-plane `:sensitive` / `:large`
  effects a `reg-event` returns, `:source :effect`) from the named frame's
  runtime-db partition; the `:frame variant-id` opts slot is load-bearing.

  ## Non-live runtime/captured value scrub (`scrub-frame-value`, rf2-12f2q)

  The wire-elision contract (`tools/story/spec/006-MCP-Surface.md`)
  promises EVERY Story-MCP payload crosses elided — not just the three
  live-state tools'. The NON-live tools also cross runtime/captured
  VALUES that can sit at a frame's frame-declared `:sensitive` `:app-db`
  paths (EP-0015 §8):
  `explain-variant`'s plan-RESOLVED value slots (`:effective-args` /
  `:args` / `:substitutions` / `:network` / `:db-seed`) and
  `record-as-variant`'s `:captured` event vectors (+ the `:play-snippet`
  rendered from them). `scrub-frame-value` gives those payloads the SAME
  PATH-based projection `scrub-rendered` applies to live derived trees,
  keyed to the variant frame — it reads the frame's app-db itself rather
  than receiving it. Author-published STATIC registration metadata
  (story/variant bodies, registry enumerations, the explain plan-
  STRUCTURE slots) is intentionally public and NOT scrubbed; see
  `scrub-frame-value` for the runtime-vs-authored split.

  ## Path-based redaction (`elide-app-db`, `scrub-assertions+count`)

  Apply the cross-MCP privacy-posture rules to every live `:app-db`
  slice and assertion accumulator before egress. Off-box defaults
  (frame-declared sensitive paths return `:rf/redacted`; assertion
  records stamped `:sensitive? true` are dropped). The shared
  `:include-sensitive` arg is the documented opt-in escape hatch.

  ## Derived-tree PATH-based projection (`scrub-rendered`, EP-0025 fail-open)

  `elide-app-db` redacts the `:app-db` slot. The same value also appears in
  `:rendered-hiccup` (the variant view renders `[:input {:value <token>}]`),
  in `:effective-args` (the resolved arg map), and in any `:snapshot` body.
  Some of those derived positions still match a classified path (an
  `:effective-args {:token …}` slice with `[:token]` classified); others
  re-key the value to a position the path cannot reach (the token at hiccup
  `[1 :value]`, not at `[:user :token]`).

  EP-0025 REMOVED the value-match (taint-by-equality) redaction that used to
  scrub the re-keyed copies — value-match is propagation/taint by another
  name, which a hygiene helper does not earn (§\"What is removed\"). The
  derived-tree projection is PATH-BASED: `elide-wire-value` walks the tree
  against the frame's classification, redacting a value AT a classified path
  and leaving a RE-KEYED copy RAW (INTENDED FAIL-OPEN). A consumer that needs
  a re-keyed value redacted must classify its app-db PATH so the derived slot
  lands the value AT that path. The `:include-sensitive` opt-out forwards the
  raw tree (same escape hatch as `:app-db`).

  ## One record-level boundary — `project-egress` (EP-0025 B4, rf2-ojp8pi)

  The derived-tree projection runs through the SINGLE public boundary
  `re-frame.core/project-egress` — the `:rf.observe/derived-tree` record kind,
  naming the off-box `:rf.egress/profile` rather than a hand-resolved
  `:rf.size/*` floor. `project-egress` resolves the profile to the egress
  floor and PATH-walks the tree against the SAME per-frame classification
  registry the `:app-db` path walker reads (frame- /
  EP-0025-commit-plane-effect- / flow-sourced declarations, unioned at
  lookup). The scrubbers below are ORCHESTRATION only — build the
  derived-tree record (its `:source-db`), name the profile, apply the
  `:include-sensitive` opt-out. This keeps the SECOND place EP-0015 egress
  semantics could drift removed (EP-0015 best-practice review issue 2)."
  (:require [re-frame.core :as rf]
            [re-frame.mcp-base.egress :as base-egress]
            [re-frame.mcp-base.elision :as base-elision]
            [re-frame.mcp-base.envelope :as base-envelope]
            [re-frame.mcp-base.sensitive :as sensitive]
            [re-frame.story-mcp.tools.result :as result]))

;; ---------------------------------------------------------------------------
;; Named `:rf.egress/*` profile adoption (EP-0015 §10, rf2-qus09h).
;;
;; story-mcp is an off-box MCP/AI tool wire — the same boundary class as
;; re-frame2-pair-mcp. Per EP-0015 §10 the egress posture names a
;; `:rf.egress/*` profile, resolved to its `:rf.size/*` floor by the
;; cross-MCP `re-frame.mcp-base.egress` mirror (pinned byte-identical to
;; the framework `re-frame.projection` table by the mcp-conformance
;; wire-vocab gate). The boolean `include?` each tool computes
;; (`(and (sensitive-reads-allowed?) per-call-include-sensitive)`) selects
;; the boundary: not-opted-in ⇒ `:rf.egress/off-box-tool` (redact
;; sensitive, elide large, structural digests on); the trusted-local
;; opt-in ⇒ `:rf.egress/local-raw` (sensitive AND large pass through).
;; The server expresses "which boundary is this", never a hand-rolled
;; `:rf.size/*` combination.
;; ---------------------------------------------------------------------------

(defn posture->profile
  "Resolve the off-box egress POSTURE to a named `:rf.egress/*` profile
  (EP-0015 §10, rf2-qus09h). `include?` is the already-gated,
  already-opted-in boolean: `false` ⇒ `:rf.egress/off-box-tool` (the
  default MCP/AI tool wire); `true` ⇒ `:rf.egress/local-raw` (the
  trusted-local operator's deliberate raw read). Mirror of
  re-frame2-pair-mcp's `tools.elision/posture->profile`."
  [include?]
  (if include?
    :rf.egress/local-raw
    :rf.egress/off-box-tool))

(defn posture->elision-opts
  "The `:rf.size/*` opt-set `elide-wire-value` is called under for the
  resolved egress posture (EP-0015 §10, rf2-qus09h) — the named profile's
  floor from the cross-MCP `mcp-base.egress` mirror. `:rf.egress/off-box-tool`
  redacts sensitive + elides large + emits structural digests;
  `:rf.egress/local-raw` opts both back in."
  [include?]
  (base-egress/profile-size-opts (posture->profile include?)))

;; ---------------------------------------------------------------------------
;; Path-based redaction
;; ---------------------------------------------------------------------------

(defn elide-app-db
  "Run `app-db` through `re-frame.core/elide-wire-value` against
  `variant-id`'s frame registry, under the named-egress profile the
  posture resolves to (EP-0015 §10, rf2-qus09h). Returns the elided value,
  or the input unchanged when `include?` is true.

  The egress walker reads `variant-id`'s per-frame elision registry
  (`[:rf.runtime/elision :sensitive-declarations]` / `:declarations`),
  written by the EP-0025 commit-plane `:sensitive` / `:large` classification
  effects (`:source :effect`, a `reg-event` returns them alongside `:db`). No
  per-read refresh is needed — the declarations are durable frame state, live
  from classification onward; the former schema→registry population hook
  (`:elision/populate-from-schemas!`) was removed with the §8 schema-attached
  app-db egress route.

  The walk runs under the `:rf.egress/off-box-tool` profile floor
  (`posture->elision-opts`): sensitive redacts to `:rf/redacted`, large
  elides to `:rf.size/large-elided` (with the structural digest off-box-tool
  carries), seeded at `variant-id`'s frame.

  Two short-circuits avoid pointless work:

    - Nil-safe — a nil `app-db` returns immediately (the walker treats
      nil as a non-elidable scalar, but we pre-check to avoid the
      registry lookup on the empty-frame happy path).

    - `include? true` returns the input unchanged. The trusted-local
      `:rf.egress/local-raw` floor flips both inclusion knobs on, so the
      walker yields `v` at every node (per `elide-wire-value`'s
      composition rule: `sensitive?` and `large?` both return `v` when
      their inclusion flag is true; no marker emit, no frame-owned
      elision, no warning). The walk is a pure no-op — full traversal,
      zero edits — so we skip it. The escape hatch should be free."
  [app-db variant-id include?]
  (cond
    (nil? app-db) app-db
    include?      app-db
    :else         (rf/elide-wire-value
                    app-db
                    (assoc (posture->elision-opts include?) :frame variant-id))))

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

;; ---------------------------------------------------------------------------
;; Derived-tree PATH-based projection (EP-0025 fail-open, rf2-ojp8pi)
;; ---------------------------------------------------------------------------
;;
;; `elide-app-db` redacts the `:app-db` slot by PATH. A derived tree
;; (`:rendered-hiccup`, `:effective-args`, a `:snapshot` body, the explain
;; plan-resolved value slots) re-surfaces the same app-db value at a position
;; that MAY or MAY NOT still match a classified path.
;;
;; EP-0025 REMOVED the value-match (taint-by-equality) engine that used to
;; collect live values at the declared paths and substitute any matching leaf
;; (§"What is removed": value-match is propagation/taint by another name). The
;; derived-tree projection is now PATH-BASED: each tree (or named slot) is
;; walked through `elide-wire-value` against the frame's classification. A
;; value AT a classified path within the tree redacts; a value RE-KEYED to a
;; position the path cannot reach ships RAW (fail-open).
;;
;; The projection runs through the SINGLE public boundary
;; `re-frame.core/project-egress` (EP-0025 B4, rf2-ojp8pi) — the
;; `:rf.observe/derived-tree` record kind, the path-based dual of
;; `elide-wire-value`. story-mcp keeps only the ORCHESTRATION: build the
;; derived-tree record (its `:source-db`), name the off-box egress profile,
;; apply the `:include-sensitive` opt-out.

(defn scrub-rendered
  "PATH-redact a DERIVED tree (rendered hiccup, `:effective-args`, a snapshot
  body) before wire egress, keyed to `variant-id`'s frame — in ONE call to the
  framework boundary `re-frame.core/project-egress` (rf2-ojp8pi), the
  `:rf.observe/derived-tree` record's SINGLE-TREE form (`:slot-keys nil`).

  EP-0025 FAIL-OPEN: the value-match (taint-by-equality) engine is REMOVED. The
  projection walks `tree` through `elide-wire-value` against `variant-id`'s
  classification registry (frame- / commit-plane-effect- / flow-sourced
  declarations, unioned) on BOTH the `:sensitive` and `:large` axes. A value
  that occupies a CLASSIFIED app-db path WITHIN `tree` (a derived slot whose
  shape mirrors the app-db — e.g. an `:effective-args {:token …}` slice with
  `[:token]` classified) redacts to `:rf/redacted` / elides to the
  `:rf.size/large-elided` marker. A value RE-KEYED to a position the path
  cannot reach (a token at hiccup `[1 :value]`, a blob at `[:pre blob]`) is
  NOT covered and ships RAW — INTENDED FAIL-OPEN. The `app-db` arg is retained
  as the record's `:source-db` but no longer seeds a value-candidate set; the
  redaction is purely positional.

  Short-circuits, mirroring `elide-app-db`:

    - `include? true` returns `tree` unchanged (the opt-out escape hatch —
      covers BOTH axes, matching the `:rf.egress/local-raw` floor).
    - A nil `tree` or non-live frame returns `tree` (the framework helper runs
      the walk ONLY against a live frame; a derived-tree walk must never fail
      CLOSED to a whole-tree sentinel)."
  [tree app-db variant-id include?]
  (if include?
    tree
    ;; Project through the SINGLE record-level boundary `re-frame.core/project-egress`
    ;; (EP-0025 B4, rf2-ojp8pi): a `:rf.observe/derived-tree` record naming the
    ;; off-box-tool egress PROFILE — `project-egress` resolves the profile to
    ;; the `:rf.egress/off-box-tool` floor and PATH-walks the tree against
    ;; `variant-id`'s classification registry (sensitive first — it wins — then
    ;; large over survivors). A value AT a classified path redacts; a re-keyed
    ;; copy ships raw (EP-0025 fail-open). Naming the PROFILE (not the
    ;; `:rf.size/*` floor) keeps the egress vocabulary in the framework
    ;; boundary, not hand-rolled here.
    (rf/project-egress
      {:kind      :rf.observe/derived-tree
       :frame     variant-id
       :tree      tree
       :source-db app-db}
      {:rf.egress/profile (posture->profile false)})))

(defn scrub-explain-values
  "PATH-redact the runtime/seeded VALUE slots `value-slot-keys` of an `explain`
  map against `variant-id`'s classification, before the explain payload crosses
  the AI/off-box boundary (rf2-12f2q, rf2-q8ebq.1) — in ONE call to the
  framework boundary `re-frame.core/project-egress` (rf2-leggev, boundary-named
  rf2-ojp8pi), the `:rf.observe/derived-tree` record's MULTI-SLOT form (each
  present key of `value-slot-keys` walked at the frame's classification path).

  EP-0025 FAIL-OPEN: the value-match engine — and the pre-frame `:db-seed`
  candidate-union it relied on — is REMOVED. Each named slot is PATH-walked: a
  value that occupies a classified path WITHIN the slot redacts (a `:db-seed`
  or `:effective-args` slice whose shape mirrors the app-db reaches the path),
  while a value RE-KEYED to a non-matching position (a `:network` reply, a
  `:sub-overrides` override value, a step payload lacking the path's parent
  keys) ships RAW. A pre-frame `:db-seed` secret is covered ONLY because its
  own shape mirrors the app-db — NOT because the seed is unioned into a
  candidate set. A consumer that needs a re-keyed value redacted must classify
  its app-db PATH so the derived slot lands the value AT that path.

  `include?` opts out (the `--allow-sensitive-reads` + per-call escape
  hatch) — when true the raw values cross (the operator signed off; BOTH axes)."
  [explain variant-id value-slot-keys include?]
  (if (or include? (nil? explain))
    explain
    ;; Project through `re-frame.core/project-egress` (EP-0025 B4, rf2-ojp8pi)
    ;; as a MULTI-SLOT `:rf.observe/derived-tree` record: `:slot-keys` names the
    ;; runtime/seeded value slots to PATH-walk, and `:source-db` is the live
    ;; variant-frame app-db (retained as the record's source; the projection is
    ;; positional, not value-candidate-driven). `project-egress` resolves the
    ;; off-box-tool profile to the egress floor and path-redacts each present
    ;; slot against `variant-id`'s registry.
    (rf/project-egress
      {:kind      :rf.observe/derived-tree
       :frame     variant-id
       :tree      explain
       :slot-keys value-slot-keys
       :source-db (rf/app-db-value variant-id)}
      {:rf.egress/profile (posture->profile false)})))

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
;; Those tools route their value-bearing slots through the SAME PATH-based
;; derived-tree projection `scrub-rendered` applies to the live derived
;; trees (rf2-12f2q), keyed to the variant frame's classification. EP-0025
;; FAIL-OPEN: a value that occupies a classified path WITHIN the slot redacts;
;; a value re-keyed to a non-matching position (a captured-event payload, a
;; `:network` reply) ships RAW. The wire-elision contract in
;; `tools/story/spec/006-MCP-Surface.md` is now "every Story-MCP payload is
;; PATH-projected; re-keyed copies are fail-open" — see that spec for the
;; EP-0025 graduation.
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
  "PATH-redact a non-live, value-bearing payload `tree` keyed to variant
  `variant-id`'s frame, before wire egress (rf2-12f2q). Reads the frame's live
  `:app-db` itself (via `re-frame.core/app-db-value`) — the non-live handlers do
  not already hold it — to seed the projection's `:source-db`.

  Thin wrapper over `scrub-rendered`: same PATH-based projection through
  `re-frame.core/project-egress` (the `:rf.observe/derived-tree` boundary).
  EP-0025 FAIL-OPEN — a value at a classified path WITHIN `tree` redacts; a
  value re-keyed to a non-matching position ships RAW. The only difference from
  `scrub-rendered` is that this reads the source app-db rather than receiving
  it — when the frame has not been allocated (`app-db-value` ⇒ nil) the
  framework helper's non-live-frame short-circuit returns the payload unwalked.

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
