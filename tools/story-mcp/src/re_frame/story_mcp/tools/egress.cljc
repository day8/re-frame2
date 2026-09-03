(ns re-frame.story-mcp.tools.egress
  "Wire-egress scrubbers for Story-MCP tool handlers.

  Per spec/Tool-Pair.md §Direct-read privacy posture (lines 544-566):
  every pair-shaped tool surfacing live frame state MUST route the
  value through `re-frame.core/elide-wire-value` (or the PATH-BASED
  derived-tree projection below) before the value crosses the wire
  egress.

  ## Path projection: fail open for re-keyed values, fail closed without a frame

  EP-0025 REMOVED value-match (taint-by-equality) redaction of re-keyed
  copies (§\"What is removed\": value-match is propagation/taint by another
  name, which a hygiene helper does not earn). Both the path-based `:app-db`
  egress AND the derived-tree projection are now PATH-BASED ONLY. Under a LIVE
  variant frame, a value at a CLASSIFIED app-db path redacts in the `:app-db`
  slice AND in any derived slot WHERE the value still occupies that path (a slot
  whose shape mirrors the app-db, e.g. an `:effective-args {:token …}` slice
  with `[:token]` classified, or a `:db-seed` that mirrors app-db). A value
  RE-KEYED to a position the classification path cannot reach (a token copied
  into rendered hiccup at `[1 :value]`, into a `:network` reply, into a
  captured-event payload) is NOT covered and ships RAW — INTENDED FAIL-OPEN
  (hygiene, not a guarantee). A consumer that needs a value redacted in a
  derived tree must classify its app-db PATH so the value lands AT that path.

  The fail-open is scoped to a LIVE variant frame. At the FRAMEWORK boundary
  (`re-frame.core/project-egress`), a derived-tree with NO live frame now FAILS
  CLOSED: the whole tree redacts to `:rf/redacted`
  rather than ship raw — the earlier carve-out that shipped the raw tree on a
  non-live frame was retired. `scrub-rendered` (the LIVE-state tools'
  `:snapshot` / `:effective-args` / evidence trees) and the live arm of
  the re-keyed-runtime scrub therefore redact-or-fail-closed by frame liveness.

  ## The Story-MCP re-keyed-runtime egress exception (`scrub-re-keyed-runtime`)

  ONE story-mcp surface scrubs against a routinely NON-LIVE variant frame —
  `read-a11y-violations` (the browser-panel axe-core nodes). Its payload is
  inherently RE-KEYED (DOM `:html`), so a path-scrub is a no-op
  for it EVEN under a live frame (EP-0025 fail-open ships it raw under a live
  frame regardless). Routing it through the now-fail-closed framework boundary
  would redact the WHOLE violations payload to `:rf/redacted`
  and destroy the tool
  without closing any leak a live frame would have closed.

  The current rule is a narrow hybrid: keep scrub-if-live, and carve this
  re-keyed runtime payload class out of the fail-closed
  boundary under a NAMED, narrow Story-MCP exception — `scrub-re-keyed-runtime`.
  It is NOT a broad `:rf.egress/local-raw` profile and NOT a general raw escape
  hatch: it applies ONLY to an inherently-re-keyed runtime payload class whose
  path-scrub is a no-op even live (axe DOM nodes). The
  framework `project-egress` boundary STAYS fail-closed; this is a
  story-mcp-local carve-out for exactly that class. See
  `scrub-re-keyed-runtime` for the full rationale.

  In story-mcp the two surfaces that ship live-state reads are
  `preview-variant` / `run-variant` (which return the variant frame's
  `:app-db` slice) and `read-failures` (which returns the variant
  frame's `:rf.story/assertions` accumulator). The walker reads the
  per-frame `[:rf.runtime/elision]` registries (EP-0025 — durable
  classification declared via the commit-plane `:sensitive` / `:large`
  effects a `reg-event` returns, `:source :effect`) from the named frame's
  runtime-db partition; the `:frame variant-id` opts slot is load-bearing.

  ## Non-live runtime and captured values

  The wire-elision contract (`tools/story/spec/006-MCP-Surface.md`)
  promises EVERY Story-MCP payload that carries OBSERVED RUNTIME state
  crosses elided — not just the three live-state tools'. The NON-live
  runtime tool `read-a11y-violations` also crosses captured VALUES that
  can sit at a frame's frame-declared `:sensitive` `:app-db` paths
  (EP-0015 §8): its axe DOM nodes. That payload class gets the SAME
  PATH-based projection `scrub-rendered` applies to live derived trees,
  keyed to the variant frame — reading the frame's app-db itself rather
  than receiving it. The inherently-re-keyed class (axe DOM)
  takes the narrow `scrub-re-keyed-runtime` exception on a non-live
  frame. Author-published
  STATIC registration metadata (story/variant bodies, registry enumerations,
  and `explain-variant`'s entire `:explain` map) is
  intentionally public and NOT scrubbed; see `scrub-re-keyed-runtime` for the
  runtime-vs-authored split. NOTE `explain-variant` is a NO-RUN tool over the
  registry side-table: even its plan-RESOLVED value slots (`:effective-args`
  / `:args` / `:substitutions` / `:network` / `:db-seed` / `:sub-overrides` /
  `:setup-order` / `:script-order`) are static author data, so the WHOLE map
  ships raw like `get-variant` — it does not route through
  any egress boundary here.

  ## Path-based redaction (`elide-app-db`, `scrub-assertions+count`)

  Apply the cross-MCP privacy-posture rules to every live `:app-db`
  slice and assertion accumulator before egress. Off-box defaults
  (frame-declared sensitive paths return `:rf/redacted`; assertion
  records stamped `:sensitive? true` are dropped). The shared
  `:include-sensitive` arg is the documented opt-in escape hatch.

  ## Derived-tree PATH-based projection (`scrub-rendered`, EP-0025 fail-open)

  `elide-app-db` redacts the `:app-db` slot. The same value also appears in
  `:effective-args` (the resolved arg map), in any `:snapshot` body, and in
  the evidence trees (a `:narrative` beat's `:db-before`, a `:sub-runs`
  `:value`).
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

  ## One record-level boundary: `project-egress`

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
;; Named `:rf.egress/*` profile adoption (EP-0015 §10).
;;
;; story-mcp is an off-box MCP/AI tool wire — the same boundary class as
;; re-frame2-pair-mcp. Per EP-0015 §10 the egress posture names a
;; `:rf.egress/*` profile, resolved to its `:rf.size/*` floor by the
;; cross-MCP `re-frame.mcp-base.egress` mirror (pinned byte-identical to
;; the framework `re-frame.projection` table by the mcp-conformance
;; wire-vocab gate). The boolean `include?` each tool computes
;; (`(and (sensitive-reads-allowed?) per-call-include-sensitive)`) is
;; mapped to the boundary by the SHARED pure fn
;; `re-frame.mcp-base.egress/mcp-tool-profile`: not-opted-in ⇒
;; `:rf.egress/off-box-tool` (redact sensitive, elide large, structural
;; digests on); the trusted-local opt-in ⇒ `:rf.egress/local-raw`
;; (sensitive AND large pass through). The mapping lives in mcp-base so
;; story-mcp and re-frame2-pair-mcp cannot drift; this server calls it
;; only AFTER its own operator + per-call permission checks. The server
;; expresses "which boundary is this", never a hand-rolled `:rf.size/*`
;; combination.
;; ---------------------------------------------------------------------------

(defn posture->elision-opts
  "The `:rf.size/*` option set used by `elide-wire-value` for the
  resolved egress posture — the named profile's floor from the cross-MCP
  `mcp-base.egress` mirror. The posture→profile mapping is the shared
  `re-frame.mcp-base.egress/mcp-tool-profile` (called here only AFTER this
  server's permission gate). `:rf.egress/off-box-tool` redacts sensitive +
  elides large + emits structural digests; `:rf.egress/local-raw` opts both
  back in."
  [include?]
  (base-egress/profile-size-opts (base-egress/mcp-tool-profile include?)))

;; ---------------------------------------------------------------------------
;; Path-based redaction
;; ---------------------------------------------------------------------------

(defn elide-app-db
  "Run `app-db` through `re-frame.core/elide-wire-value` against
  `variant-id`'s frame registry, under the named-egress profile the
  posture resolves to. Returns the elided value,
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
  cannot tell how much the egress filtered. This closes the
  silent-swallow failure mode.

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
;; Derived-tree path-based projection (EP-0025 fail-open)
;; ---------------------------------------------------------------------------
;;
;; `elide-app-db` redacts the `:app-db` slot by PATH. A derived tree
;; (`:effective-args`, a `:snapshot` body, an evidence tree, the explain
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
;; `re-frame.core/project-egress` — the
;; `:rf.observe/derived-tree` record kind, the path-based dual of
;; `elide-wire-value`. story-mcp keeps only the ORCHESTRATION: build the
;; derived-tree record (its `:source-db`), name the off-box egress profile,
;; apply the `:include-sensitive` opt-out.

(defn scrub-rendered
  "PATH-redact a DERIVED tree (rendered hiccup, `:effective-args`, a snapshot
  body) before wire egress, keyed to `variant-id`'s frame — in ONE call to the
  framework boundary `re-frame.core/project-egress`, the
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
      covers BOTH axes, matching the `:rf.egress/local-raw` floor; this is the
      ONE deliberate way to ship a frameless tree raw off-box).
    - A nil `tree` returns `tree` (nothing to walk).
    - An EMPTY collection `tree` (`[]` / `{}` / `#{}`) returns `tree` unchanged:
      an empty derived tree carries nothing to protect on any
      frame, so it must NOT trip the non-live fail-closed branch below and
      redact the whole (empty) tree to the `:rf/redacted` KEYWORD. That keyword
      would break a downstream schema expecting a sequential — the
      `run-variant` error branch mints `[]` evidence slots via
      `story/run-result`, and a `:rf/redacted` where the frozen
      `[:sequential :any]` schema requires a sequential re-breaks the exact
      invariant the `(vec …)` guard holds in testing.cljc. `(coll? tree)`
      excludes scalars (a string is not a
      `coll?`); the nil case is already handled above.
    - A NON-LIVE variant frame (nil / unknown / destroyed) FAILS CLOSED:
      `project-egress` redacts the whole tree to
      `:rf/redacted` rather than ship it raw. A missing variant frame must NOT
      silently become raw off-box — the EP-0025 fail-open is scoped to a re-keyed
      VALUE under a LIVE governing frame, never to an unresolvable frame. A
      consumer that needs the raw tree off a non-live frame opts in via
      `include?` (the `--allow-sensitive-reads` escape hatch)."
  [tree app-db variant-id include?]
  (cond
    ;; Nil has nothing to protect and should not depend on frame liveness.
    (nil? tree) tree

    ;; An empty derived tree has nothing to protect on any frame, so return it
    ;; unchanged rather than route it through the non-live fail-closed branch
    ;; below — which would redact even an empty `[]` to the `:rf/redacted`
    ;; keyword and break the `[:sequential :any]` shape the run-variant error
    ;; branch's `[]` evidence slots must keep. `(coll? tree)`
    ;; excludes scalars; nil is handled above.
    (and (coll? tree) (empty? tree)) tree

    include? tree

    :else
    ;; Project through the single record-level boundary `re-frame.core/project-egress`:
    ;; a `:rf.observe/derived-tree` record naming the
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
      {:rf.egress/profile (base-egress/mcp-tool-profile false)})))

;; ---------------------------------------------------------------------------
;; Non-live runtime and captured values
;; ---------------------------------------------------------------------------
;;
;; The three live-state tools (`preview-variant` / `run-variant` /
;; `read-failures`) hold the post-run `:app-db` in hand and feed it to
;; `elide-app-db` (path) + `scrub-rendered` (value). But one NON-live
;; RUNTIME tool also crosses the AI/off-box boundary carrying captured VALUES
;; that can sit at a frame's declared-`:sensitive?` paths:
;;
;;   - `read-a11y-violations`'s in-browser axe-core violation nodes (the
;;     variant frame may not be live in the JVM tool process).
;;
;; `explain-variant` is not in this list: it is a no-run tool
;; over the registry side-table, so its `:explain` map — plan-STRUCTURE AND
;; plan-RESOLVED value slots alike — is static AUTHOR data, not observed
;; runtime. It ships RAW like `get-variant` / `variant->edn` (see the
;; INTENTIONALLY-PUBLIC list below); no egress boundary applies.
;;
;; The payload is a RE-KEYED runtime payload — axe DOM nodes.
;; The secret rides a position the app-db classification path cannot reach
;; (a node `:html` outerHTML), so a PATH-scrub is a NO-OP
;; for it EVEN under a live frame (EP-0025 fail-open ships it raw under
;; a live frame regardless). Routing it through the now-fail-closed
;; framework boundary on a non-live frame would redact the WHOLE payload to
;; `:rf/redacted` and destroy the tool WITHOUT closing any real leak. It
;; takes the NAMED Story-MCP `scrub-re-keyed-runtime` exception: live ⇒
;; PATH-project; non-live ⇒ raw under the documented carve-out.
;;
;; The framework `project-egress` boundary STAYS fail-closed; this is the only
;; story-mcp-local carve-out, narrow to that re-keyed runtime payload class.
;;
;; INTENTIONALLY-PUBLIC (NOT scrubbed): the docs-discovery surfaces that
;; return author-published STATIC registration prose — `get-story` /
;; `get-variant` / `variant->edn` bodies, `list-stories` / `list-modes` /
;; `list-decorators` / `list-tags` / `list-assertions` enumerations, the
;; markdown render, and `explain-variant`'s ENTIRE `:explain` map — both its
;; plan-STRUCTURE slots (`:source-chain` / `:parent-chain` / `:compose` /
;; `:merge` / `:strict-conflicts` / `:tags` / `:platforms` / …) AND its
;; plan-RESOLVED value slots (`:effective-args` / `:args` / `:substitutions` /
;; `:network` / `:db-seed` / `:sub-overrides` / `:setup-order` /
;; `:script-order`) — since `explain-variant` is a NO-RUN projection over the
;; registry, every slot is static author data.
;; Those are the catalogue an author publishes for
;; discovery — not runtime/user state — and the threat model
;; (`spec/015-Data-Classification.md`) scopes the marks to the OBSERVED
;; runtime, not authored registration data. Registry-wide enumerations
;; (modes, decorators) are not frame-keyed and carry no runtime values;
;; their `:args` / `:app-db-patch` / `:response` slots are the author's
;; own published fixture data. See `tools/story/spec/006-MCP-Surface.md`
;; §Wire-elision boundary for the single-sourced classification.

(defn variant-frame-live?
  "True when `variant-id` names a registered, non-destroyed frame (the
  framework's frame-liveness predicate, `re-frame.core/frame-ids`). The
  derived-tree projection redacts by PATH only against a LIVE frame's
  classification; a non-live frame is the fail-closed boundary
  (and the gate for the `scrub-re-keyed-runtime` exception)."
  [variant-id]
  (contains? (rf/frame-ids) variant-id))

(defn scrub-re-keyed-runtime
  "PATH-redact an inherently re-keyed runtime payload `tree` against
  `variant-id`'s frame before wire egress. This named, narrow exception
  applies to exactly one runtime payload class:

    - `read-a11y-violations`'s axe-core DOM nodes (the secret rides a node
      `:html` outerHTML, a non-app-db position).

  It is NOT a broad `:rf.egress/local-raw` profile and NOT a general raw escape
  hatch. It applies only to this re-keyed runtime class whose PATH-scrub is a
  no-op even under a live frame; any other payload must use the fail-closed
  `scrub-rendered` boundary.

  Reads the frame's live `:app-db` itself (via `re-frame.core/app-db-value`) —
  the non-live handlers do not already hold it — to seed the projection's
  `:source-db`.

  ## LIVE variant frame — PATH-projected (scrub-if-live)

  When `variant-id`'s frame IS live (the panel recorded against it; a
  run/preview allocated it), this is a thin wrapper over `scrub-rendered`: the
  same PATH-based projection through `re-frame.core/project-egress` (the
  `:rf.observe/derived-tree` boundary). EP-0025 FAIL-OPEN under a live frame — a
  value at a classified path WITHIN `tree` redacts; a value re-keyed to a
  non-matching position ships RAW.

  ## NON-LIVE variant frame — RAW under the named exception (the carve-out)

  `read-a11y-violations`
  reads the in-browser panel's stored axe-core nodes — it does not allocate the
  target variant frame, so `variant-id`'s frame is routinely NON-LIVE here.
  Its payload is inherently RE-KEYED (DOM `:html`): a
  path-scrub is a NO-OP for it EVEN under a live frame (EP-0025 fail-open), so
  the value ships raw under a live frame regardless.

  The framework `project-egress` fails closed on a non-live frame, which is
  correct for an app-db-shaped tree that could path-redact under a live frame.
  Routing this inherently re-keyed non-live payload through that boundary would
  redact the WHOLE violations payload to `:rf/redacted` —
  destroying the tool's primary function —
  without closing any real leak the live-frame case would have
  closed. So a non-live frame returns the payload RAW under this named, narrow
  exception (NOT a general escape hatch): the leak-delta versus the live-frame
  case is zero, because the live case already ships these re-keyed copies raw.
  The framework boundary stays fail-closed; this is a story-mcp-local carve-out
  for exactly this re-keyed runtime class.

  `include?` is the same `--allow-sensitive-reads` + per-call
  `:include-sensitive` opt-out the live tools honour — when true the raw
  value crosses (the operator signed off on the egress posture)."
  [tree variant-id include?]
  (cond
    include?    tree
    (nil? tree) tree
    ;; LIVE frame ⇒ PATH-project through the (now fail-closed) framework
    ;; boundary; a live frame redacts by path / fail-opens a re-keyed value.
    (variant-frame-live? variant-id)
    (scrub-rendered tree (rf/app-db-value variant-id) variant-id include?)
    ;; NON-LIVE frame ⇒ the named re-keyed-runtime exception. Return raw: the
    ;; payload is inherently re-keyed, so the live case already ships it raw and
    ;; fail-closing here would destroy the tool with zero leak-delta.
    :else       tree))

;; ---------------------------------------------------------------------------
;; Wire-egress indicator counts.
;;
;; story-mcp's egress drops `:sensitive? true` assertion records and
;; replaces over-threshold / schema-`:large?` leaves with the
;; `:rf.size/large-elided` marker. spec/Conventions.md §Cross-MCP indicator-field
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
  elided slot — `:app-db`, `:snapshot`, `:effective-args`, the evidence
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
  required egress indicator counts. The
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
