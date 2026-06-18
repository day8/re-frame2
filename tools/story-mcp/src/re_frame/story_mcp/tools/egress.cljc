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
  frame-owned `[:rf.runtime/elision]` registries (EP-0015 §8 — durable
  classification declared at `reg-frame` time via
  `re-frame.frame-classification`) from the named frame's runtime-db
  partition; the `:frame variant-id` opts slot is load-bearing.

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
  value-based redaction `scrub-rendered` applies to live derived trees,
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

  ## Derived-tree value-based redaction (rf2-ee38b.17, `scrub-rendered`)

  `elide-app-db` closes the leak for the `:app-db` slot — but the same
  sensitive value reappears, VERBATIM, in `:rendered-hiccup` (the variant
  view renders `[:input {:value <token>}]`), in `:effective-args` (the
  resolved arg map that fed the render), and in any `:snapshot` body.
  Those are derived from the same app-db, but they are NOT keyed by
  app-db path — the token sits at a hiccup-tree position
  (`[1 :value]`), not at `[:user :token]`. `elide-wire-value` matches
  the frame-declared SENSITIVE PATHS, so running it over a hiccup
  tree finds nothing: the path-based walker is structurally blind to
  the re-keyed copy.

  The sound posture for a DERIVED tree is VALUE-based redaction:
  collect the live values sitting at the frame's frame-declared
  `:sensitive` app-db paths, then substitute any leaf in the derived tree that
  EQUALS one of them with the same `:rf/redacted` sentinel
  `elide-wire-value` emits. This honours the Tool-Pair §Direct-read
  privacy MUST intent — 'live runtime state crossing the MCP egress
  is scrubbed' — for the rendered surface, with the same
  `:include-sensitive` opt-out escape hatch as `:app-db`.

  Value-matching is a heuristic; its one collateral hazard is a
  sensitive path holding a SHORT/COMMON scalar (`0`, `200`, `:ok`),
  which would scrub every benign leaf that equals it. The framework
  composed helper `re-frame.core/redact-derived-slots` guards that
  (rf2-g7cd1, hardened rf2-f3kf7; centralized rf2-i783h0; composed
  rf2-leggev) by dropping any candidate that ALSO appears, VERBATIM, in the
  POST-elision `:app-db` (the actual wire bytes) — such a value is already
  disclosed by the path-based `:app-db` egress, so excluding it leaks nothing
  new while restoring the benign leaves. Classifying against the elided db
  (not the raw db) is load-bearing: a secret aliased into a `:large?`-declared
  subtree is replaced by the `:rf.size/large-elided` marker on the wire, so it
  is NOT disclosed and MUST stay redacted. See the framework helper for the
  fail-SAFE argument.

  ## Centralized + composed value-match engine (rf2-i783h0, rf2-leggev)

  The value-match-redaction ENGINE — candidate collection, the
  non-unique-secret guard, and the matching-leaf substitution, across BOTH
  the sensitive and large egress axes — lives ONCE in `re-frame.elision` (the
  value-based DUAL of `elide-wire-value`), assembled behind the SINGLE
  composed multi-slot helper `re-frame.core/redact-derived-slots`
  (rf2-leggev). The nine granular gears that used to be reached piecemeal
  through the facade are no longer facade exports. The scrubbers below are
  ORCHESTRATION only — resolve the egress floor from the posture, read the
  source app-db (and, for the no-run explain path, pass the plan's `:db-seed`
  as the helper's `:rf.elision/extra-sensitive-source`), apply the
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

  The egress walker reads `variant-id`'s frame-owned elision registry
  (`[:rf.runtime/elision :sensitive-declarations]` / `:declarations`),
  installed by `re-frame.frame-classification` at `reg-frame` time
  (EP-0015 §8). No per-read refresh is needed — the declarations are
  durable frame state, live from frame creation onward; the former
  schema→registry population hook (`:elision/populate-from-schemas!`) was
  removed with the §8 schema-attached app-db egress route.

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
;; Derived-tree value-based redaction (rf2-ee38b.17; centralized rf2-i783h0)
;; ---------------------------------------------------------------------------
;;
;; `elide-app-db` closes the leak for the `:app-db` slot by PATH — but the
;; same sensitive value reappears, VERBATIM, in `:rendered-hiccup`,
;; `:effective-args`, a `:snapshot` body, and the explain plan-resolved value
;; slots, at a non-app-db position the path-based walker can never reach. The
;; sound posture for a derived tree is VALUE-based redaction: collect the live
;; values at the frame's declared-`:sensitive?` paths and substitute any
;; matching leaf with `:rf/redacted`.
;;
;; The VALUE-MATCH ENGINE — candidate collection (mirroring the elider's
;; indexing so a seq-indexed `[:tokens 0]` is reached), the non-unique-secret
;; guard (drop any candidate already on the wire, classified against the
;; ELIDED db — rf2-g7cd1 / rf2-f3kf7), and the matching-leaf substitution —
;; now lives ONCE in the framework `re-frame.elision` ns (EP-0015 issue 2,
;; rf2-i783h0). It is the value-based DUAL of `elide-wire-value`, so it
;; belongs beside it (one home for the egress fact; a SECOND place the
;; semantics could drift is removed). story-mcp keeps only the ORCHESTRATION:
;; resolve the egress floor from the posture, read the source app-db, apply
;; the `:include-sensitive` opt-out. The behaviour is byte-identical — the
;; engine moved, the contract did not.

(defn scrub-rendered
  "Value-redact AND value-elide a DERIVED tree (rendered hiccup,
  `:effective-args`, a snapshot body) before wire egress, keyed to
  `variant-id`'s frame. EP-0015 treats `:sensitive` + `:large` as PEER egress
  axes, so the derived-tree scrub runs BOTH (rf2-9o5ixx) — in ONE call to the
  framework composed helper `re-frame.core/redact-derived-slots`
  (rf2-leggev), the SINGLE-TREE form (`slot-keys nil`):

    1. SENSITIVE: collect the live values at `variant-id`'s
       declared-`:sensitive?` paths from `app-db` — with the
       non-unique-secret guard, classified against the elided db under the
       `:rf.egress/off-box-tool` floor — and substitute any matching leaf in
       `tree` with `:rf/redacted` (rf2-ee38b.17).
    2. LARGE: collect the live values at `variant-id`'s declared-`:large`
       paths and substitute any matching leaf with the `:rf.size/large-elided`
       marker — so a large blob declared at `[:blob]` and re-keyed into
       `[:pre blob]` / a snapshot / evidence echo elides on the wire rather
       than crossing raw, and the `:elided-large` indicator count
       (`count-elided`) sees the markers.

  SENSITIVE WINS: the helper runs the sensitive pass FIRST and its large
  collector skips any node also declared sensitive, so a value that is both
  redacts to `:rf/redacted` (never the large marker). The large pass then
  sees only the sensitive-survived tree.

  Short-circuits, mirroring `elide-app-db`:

    - `include? true` returns `tree` unchanged (the opt-out escape hatch —
      covers BOTH axes, matching the `:rf.egress/local-raw` floor).
    - A nil `tree` or nil `app-db` returns `tree` (handled by the framework
      helper — nothing to scrub / no source of values).
    - No declared-sensitive/large (or all-disclosed) values ⇒ `tree` is
      returned unwalked."
  [tree app-db variant-id include?]
  (if include?
    tree
    ;; Classify against the SAME `:rf.egress/off-box-tool` floor `elide-app-db`
    ;; ships under (rf2-qus09h) so the wire-classification reasons about the
    ;; identical bytes the `:app-db` egress actually emits. The composed helper
    ;; runs sensitive first (it wins), then large over what survives, off ONE
    ;; collection pass.
    (rf/redact-derived-slots tree nil app-db variant-id
                             (posture->elision-opts false))))

(defn scrub-explain-values
  "Value-redact the runtime/seeded VALUE slots `value-slot-keys` of an
  `explain` map against `variant-id`'s declared-sensitive / -large values,
  before the explain payload crosses the AI/off-box boundary (rf2-12f2q,
  rf2-q8ebq.1, pre-frame hardening rf2-tag30h) — in ONE call to the framework
  composed helper `re-frame.core/redact-derived-slots` (rf2-leggev), the
  MULTI-SLOT form (each present key of `value-slot-keys` scrubbed off one
  collection pass).

  Candidate sensitive secrets come from BOTH sources, unioned by the helper:

    - the LIVE variant-frame app-db (the `source-db` arg — the guarded live
      reader, classified against the elided db under the
      `:rf.egress/off-box-tool` floor) — the source once `run-variant` /
      `preview-variant` has allocated and seeded the frame; AND
    - the PLAN's OWN `:db-seed` slot (passed as the helper's
      `:rf.elision/extra-sensitive-source`) — the FAIL-CLOSED pre-frame
      source. `explain-variant` is a documented no-run path: a caller can
      read it BEFORE any run allocates the frame. With only the live reader,
      a secret authored into `:db-seed` and re-surfaced in `:effective-args`
      / `:network` / a step payload would cross raw (the live app-db is nil
      ⇒ no candidates). The helper walks the seed at the frame's
      declared-sensitive paths (unguarded — fail-SAFE) and unions those
      candidates, so the no-run secret still redacts with no live frame.

  LARGE axis (rf2-9o5ixx): EP-0015 treats `:large` as a PEER egress axis, so
  each value slot is ALSO value-elided against `variant-id`'s declared-`:large`
  values — a large blob re-surfaced into `:effective-args` / `:network` / a
  step payload elides to the `:rf.size/large-elided` marker rather than
  crossing raw. The marker map is collected ONCE (from the live app-db
  `source-db`) and substituted per slot. Sensitive runs FIRST (it wins); the
  large pass sees the sensitive-survived slots. The large source is the live
  frame app-db only — the pre-frame `:db-seed` source feeds the SENSITIVE
  union (a fail-closed no-run secret), while a large classification's
  value-match is driven from the live values.

  `include?` opts out (the `--allow-sensitive-reads` + per-call escape
  hatch) — when true the raw values cross (the operator signed off; BOTH axes)."
  [explain variant-id value-slot-keys include?]
  (if (or include? (nil? explain))
    explain
    (let [app-db (rf/app-db-value variant-id)
          opts   (assoc (posture->elision-opts false)
                        :rf.elision/extra-sensitive-source (:db-seed explain))]
      (rf/redact-derived-slots explain value-slot-keys app-db variant-id opts))))

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
;; for `vk`), so the same framework `elision-sensitive-value-set` candidate
;; set (for `vk`) governs it.
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
