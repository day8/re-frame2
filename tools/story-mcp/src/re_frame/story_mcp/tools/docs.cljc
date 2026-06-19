(ns re-frame.story-mcp.tools.docs
  "Docs-category tool handlers — introspection over the Story
  registry. Per IMPL-SPEC §7.2 these are the pure-read surfaces:
  `list-stories`, `get-story`, `get-variant`, `list-tags`,
  `list-modes`, `list-assertions`, `variant->edn`.

  ## Pagination (rf2-76sf6)

  Per spec/Principles.md §'Tight token budget' MUST, every `list-*`
  tool accepts `:limit` + `:cursor`. Small registries fit on one
  page and never see the pagination metadata; large registries get
  `:total`, `:limit`, `:has-more?`, `:next-cursor` slots alongside
  the normal payload. The `get-*` / `<thing>->edn` tools are NOT
  paginated — their return is a single record bounded by the
  registered body's size, not a function of registry size."
  (:require [clojure.set :as set]
            [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.tools.cursor :as cursor]
            [re-frame.story-mcp.tools.args :as targs]
            [re-frame.story-mcp.tools.egress :as egress]
            [re-frame.story-mcp.tools.result :as result]
            [re-frame.story-mcp.tools.schemas :as s]))

(defn tool-list-stories
  "Docs: all registered stories (with optional tag filters).

  `args`:
    :tags    — vector of tag ids (strings or `:keyword` forms); narrows
               the result to stories whose `:tags` set intersects this.
    :limit   — optional, default 25. Per-page entry count (rf2-76sf6).
    :cursor  — optional opaque continuation token from a previous call.

  HOT PATH (rf2-d3iso): agents spam this tool. The variant-id slot per
  story is read from `story/variants-by-story` — a single O(V) pass
  over the variant side-table — instead of the previous O(S × V) shape
  of calling `variants-of` once per story.

  rf2-lqjbk: caller-supplied `:tags` entries route through
  `args/safe-keyword` against the registered-tag set — unknown tag
  ids skip the intersection rather than interning a fresh JVM
  keyword.

  rf2-wu1o2d: a SUPPLIED `:tags` filter is honoured even when every
  entry is unknown. We track whether the caller supplied `:tags`
  SEPARATELY from the set of tags that resolved against the registry,
  so an unknown-only filter (a typo / stale tag such as `[\":docz\"]`)
  returns an EMPTY result rather than silently widening to the full
  catalogue. The supplied-but-unresolved names are reported back in an
  `:ignored-tags` diagnostic slot (string forms, never interned) so the
  agent sees WHY the result is empty. A mixed known+unknown filter still
  applies the known tags and reports the dropped names. The
  no-interning posture is preserved — `:ignored-tags` carries the raw
  supplied strings, the keyword table is untouched.

  rf2-76sf6: pagination via `cursor/page`. The stable-sort key is the
  story id (string projection). Small registries return the bare
  `{:stories [...]}` payload; once entries exceed `:limit` the
  response adds `:total :limit :has-more? :next-cursor`."
  [args]
  (let [stories      (story/registrations :story)
        tag-set      (story/list-tags)
        supplied     (:tags args)
        supplied?    (some? supplied)
        ;; Resolve each supplied entry against the registered-tag set
        ;; WITHOUT interning unknowns (rf2-lqjbk). Partition by whether
        ;; it resolved so the unknown names can ride the `:ignored-tags`
        ;; diagnostic as their raw string forms.
        tags         (into #{} (keep #(args/safe-keyword % tag-set)) (or supplied []))
        ignored      (when supplied?
                       (into [] (remove #(args/safe-keyword % tag-set)) supplied))
        ;; A SUPPLIED filter always filters — even when nothing resolved
        ;; (unknown-only ⇒ empty intersection ⇒ empty result), so a typo
        ;; never widens to the whole catalogue (rf2-wu1o2d). Only the
        ;; no-`:tags` call returns the unfiltered registry.
        filtered     (if supplied?
                       (into {}
                             (filter (fn [[_id body]]
                                       (seq (set/intersection tags (set (:tags body))))))
                             stories)
                       stories)
        index        (story/variants-by-story)
        ;; Build the full sorted entry vec FIRST; pagination slices it.
        ;; The sort is on string projection of the id for stable cross-
        ;; page ordering — the fingerprint depends on it.
        sorted       (sort-by (comp str key) filtered)
        all-ids      (keys filtered)
        entries      (mapv (fn [[sid body]]
                             {:id   sid
                              :doc  (:doc body)
                              :tags (vec (:tags body))
                              :variants (sort (get index sid #{}))})
                           sorted)]
    (cursor/paged-result entries all-ids args "list-stories"
                         (fn [page]
                           (cond-> {:stories page}
                             (seq ignored) (assoc :ignored-tags ignored))))))

(defn tool-get-story
  "Docs: one story's full body.

  rf2-lqjbk: `:story-id` is resolved through `args/safe-keyword`
  against the registered-stories set — an unknown id returns the
  documented `Story not found` error without interning (via the shared
  `targs/with-story-id` prelude)."
  [args]
  (targs/with-story-id args
    (fn [sk]
      (result/edn-result {:id       sk
                          :body     (story/handler-meta :story sk)
                          :variants (sort (story/variants-of sk))}))))

(defn tool-get-variant
  "Docs: one variant's full body (`handler-meta :variant id`)."
  [args]
  (targs/with-variant args
    (fn [vk body]
      (result/edn-result {:id vk :body body}))))

(defn tool-list-tags
  "Docs: canonical tags + custom tags.

  rf2-76sf6: the bifurcated `{:canonical :custom :all}` shape is
  retained; `:canonical` is always the full canonical set (the seven
  spec/007 inclusion tags + the five rf2-k1k87 `:state/*` magnitude
  tags = 12 entries total; bounded and not a function of registry
  size, so pagination does not apply). `:custom` (project-registered
  tags) and `:all` (their union) are paginated when the count exceeds
  `:limit`. Small registries see no pagination metadata."
  [args]
  (let [registered (story/list-tags)
        canonical  (vec (sort-by str (into story/canonical-tags
                                           story/canonical-state-tags)))
        custom-set (set/difference (set registered) (set canonical))
        custom     (vec (sort-by str custom-set))]
    (cursor/paged-result custom custom-set args "list-tags"
                         (fn [page]
                           ;; :all is the union of :canonical + the (page-sliced)
                           ;; :custom. When no pagination kicks in (small registry)
                           ;; the result is byte-identical to the pre-rf2-76sf6 shape.
                           {:canonical canonical
                            :custom    page
                            :all       (vec (sort-by str (into canonical page)))}))))

(defn tool-list-modes
  "Docs: registered modes (from `reg-mode`). Returns each mode's id +
  body so agents can see the `:args` saved tuple.

  rf2-76sf6: paginated per spec/Principles.md §'Tight token budget'."
  [args]
  (let [modes   (story/registrations :mode)
        sorted  (sort-by (comp str key) modes)
        all-ids (keys modes)
        entries (mapv (fn [[mid body]] {:id mid :doc (:doc body) :args (:args body)})
                      sorted)]
    (cursor/paged-result entries all-ids args "list-modes"
                         (fn [page] {:modes page}))))

(defn- decorator-summary
  "Project one decorator body to the EDN-safe shape — id, kind, doc,
  and the kind-specific data slots. The `:wrap` slot of a `:hiccup`
  decorator carries a closure (Stage 2's one legal closure-bearing
  slot per `:hiccup` decorator's registration site); it is dropped
  here because closures don't transport over MCP. The `:app-db-patch`
  / `:init` (frame-setup) and `:fx-id` / `:response` (fx-override)
  slots are pure data and survive verbatim."
  [id body]
  (let [kind (:kind body)]
    (cond-> {:id   id
             :kind kind
             :doc  (:doc body)}
      (= kind :hiccup)       (assoc :has-wrap? (some? (:wrap body)))
      (= kind :frame-setup)  (assoc :init          (:init body)
                                    :app-db-patch  (:app-db-patch body))
      (= kind :fx-override)  (assoc :fx-id    (:fx-id body)
                                    :response (:response body)))))

(def ^:private decorator-kinds
  "Bounded enum allowlist for `tool-list-decorators` `:kind` filter
  (rf2-lqjbk). Three legal values per spec/Stage-2 decorator kinds;
  an unrecognised string short-circuits through `safe-keyword` without
  interning."
  #{:hiccup :frame-setup :fx-override})

(defn tool-list-decorators
  "Docs: read-only enumeration of registered decorators (rf2-mqp1u).
  Returns each decorator's id, kind, and doc plus the kind-specific
  pure-data slots. The `:wrap` closure on `:hiccup` decorators is
  not transported — only a `:has-wrap?` boolean — because closures
  don't survive EDN serialisation; agents inspecting the rendered
  result use `preview-variant` instead. There is no decorator WRITE
  tool: decorators carry closures JSON-RPC can't transport, so the
  enumeration is read-only.

  Optional `args`:

  - `:kind` (string, optional) — narrow to one decorator kind. One
    of `\"hiccup\"`, `\"frame-setup\"`, `\"fx-override\"`. Resolved
    through `args/safe-keyword` against the bounded `decorator-kinds`
    set (rf2-lqjbk) — no-intern: an unrecognised string never mints a
    fresh JVM keyword.

  rf2-cdavyf: a SUPPLIED `:kind` outside the bounded enum is an
  agent-recoverable error, NOT a silent widen to the full catalogue.
  The enum is advertised as a filter; resolving a typo (`\"hicup\"`) to
  `nil` and then treating `nil` as no-filter returned EVERY decorator —
  hiding the caller's mistake behind a successful-looking full result.
  An absent `:kind` (the slot was never sent) is still the legitimate
  no-filter path; only a PRESENT-but-unrecognised value rejects.

  rf2-76sf6: pagination via `:limit` / `:cursor`. The filtered+sorted
  entry vec is paged; the cursor's fingerprint is over the FILTERED
  id-set so a kind-filter change between pages reads as a stale
  cursor (different sig)."
  [args]
  (let [raw-kind    (:kind args)
        kind-filter (some-> raw-kind (args/safe-keyword decorator-kinds))]
    ;; rf2-cdavyf — a present-but-unrecognised `:kind` is rejected rather
    ;; than widened to all. `(some? raw-kind)` distinguishes "no filter
    ;; requested" (absent slot ⇒ `raw-kind` nil) from "filter requested
    ;; with a bad value" (`raw-kind` present but `safe-keyword` ⇒ nil).
    (if (and (some? raw-kind) (nil? kind-filter))
      (result/error-result
       (str "Unknown decorator kind: " (pr-str raw-kind)
            ". Allowed: " (pr-str (mapv name (sort decorator-kinds)))
            ". (The :kind filter is an enum; fix the value or drop the key for the full catalogue.)")
       {:rf.error :rf.story-mcp/unknown-decorator-kind
        :kind     raw-kind
        :allowed  (mapv name (sort decorator-kinds))})
      (let [decorators (story/registrations :decorator)
            filtered   (cond->> decorators
                         kind-filter (into {} (filter (fn [[_ body]]
                                                        (= kind-filter (:kind body))))))
            sorted     (sort-by (comp str key) filtered)
            all-ids    (keys filtered)
            entries    (mapv (fn [[did body]] (decorator-summary did body)) sorted)]
        (cursor/paged-result entries all-ids args "list-decorators"
                             (fn [page] {:decorators page}))))))

(def canonical-assertion-docs
  "Per spec/007 line 304 + IMPL-SPEC §3.5 the seven dispatched canonical
  assertions' arities, PLUS the tape-evaluated `:rf.assert/schema-error`
  (rf2-5x1wt.21, spec/017 §Schema rule). `:rf.assert/schema-error` is the
  one canonical assertion that is NOT dispatched into the frame — it
  declares an EXPECTED schema violation the runner exact-consumes against
  the projected epoch-tape evidence (a run FAILS on any schema violation
  unless it is exactly expected+consumed; there is no `:no-schema-errors`
  knob)."
  [{:id :rf.assert/path-equals
    :payload "[path expected]"
    :semantics "(= (get-in @app-db path) expected)"}
   {:id :rf.assert/path-matches
    :payload "[path malli-schema]"
    :semantics "Malli validate at path"}
   {:id :rf.assert/sub-equals
    :payload "[sub-vec expected]"
    :semantics "(= @(subscribe sub-vec) expected)"}
   {:id :rf.assert/dispatched?
    :payload "[event-or-pred]"
    :semantics "Was this event dispatched during play?"}
   {:id :rf.assert/state-is
    :payload "[machine-id state]"
    :semantics "Machine in state?"}
   {:id :rf.assert/no-warnings
    :payload "[]"
    :semantics "No :warning trace events since play start"}
   {:id :rf.assert/effect-emitted
    :payload "[fx-id (optional pred)]"
    :semantics "fx-id emitted during play?"}
   {:id :rf.assert/schema-error
    :payload "[{:where surface …}]"
    :semantics (str "Declares an EXPECTED schema violation on a surface "
                    "(tape-evaluated, not dispatched). The run fails on any "
                    "schema violation unless exactly expected+consumed.")}])

(defn tool-list-assertions
  "Docs: the `:rf.assert/*` canonical vocabulary + arity docs.

  rf2-76sf6: the bifurcated `{:canonical :registered}` shape is
  retained; `:canonical` is the full 8-assertion doc vector — the seven
  dispatched canonical assertions plus the tape-evaluated
  `:rf.assert/schema-error` (bounded and constant, so the pagination MUST
  does not apply).

  rf2-4sgak: `:registered` is the FULL vocabulary the Story plan compiler
  accepts — `story/known-assertion-ids` (spec/017 §Assertions),
  the SAME set `plan.cljc` validates authored assertion atoms against via
  `assertion-id-known?`. That is the eight canonical ids PLUS the
  richer-runner families the canonical doc-vec does not cover: the DOM
  family (`:rf.assert/dom-visible|dom-hidden|dom-text`), the visual / a11y
  oracles (`:rf.assert/visual-snapshot`, `:rf.assert/a11y`,
  `:rf.assert/a11y-structural`), and the reactive-count assertions
  (`:rf.assert/caused`, `:rf.assert/no-cascade-rerender`). Previously this
  slot mirrored only `canonical-assertion-ids`, so an agent could not
  discover the visual/a11y/DOM ids the compiler would have accepted and
  fell back to stale prose. `:registered` is paginated when the count
  exceeds `:limit`; small registries see no pagination metadata."
  [args]
  (let [registered (sort-by str (story/known-assertion-ids))
        reg-vec    (vec registered)]
    (cursor/paged-result reg-vec (set reg-vec) args "list-assertions"
                         (fn [page] {:canonical  canonical-assertion-docs
                                     :registered page}))))

(def ^:private explain-value-bearing-slots
  "The `explain` map slots that carry RUNTIME-RESOLVED / SEEDED VALUES
  (rf2-12f2q, extended rf2-q8ebq.1) — the slots a value-redaction step
  must scrub against the variant frame's declared-sensitive values before
  the explain payload crosses the AI/off-box boundary:

  - `:effective-args` / `:args` / `:substitutions` — the post-resolution
    arg map(s); a sensitive arg lands its value here.
  - `:network` — the per-route HTTP reply map (`{[method url] {:reply …}}`)
    the variant authored; a stub reply can carry a real token / PII.
  - `:db-seed` — the frame-setup seed data merged into the run's app-db.
  - `:sub-overrides` — the view-state subscription override map
    (`{:overrides {[query] value}}`). Override VALUES are resolved at
    plan-compile time by the SAME `substitute-args` that feeds
    `:substitutions` (story plan.cljc:1297), so a declared-sensitive arg
    pinned into an override value lands here verbatim. `scrub-frame-value`
    recurses the nested `[:overrides …]` subtree.
  - `:setup-order` / `:script-order` — the resolved setup / script step
    sequences. Both run `substitute-args` (story plan.cljc:1263/1269) —
    the IDENTICAL substitution that feeds the scrubbed `:substitutions` —
    so a sensitive arg substituted into a setup/script step's payload
    rides these post-substitution sequences. Scrubbing `:substitutions`
    while leaving these raw is a clean bypass (the secret leaks via the
    unscrubbed sibling), so they are scrubbed too (rf2-q8ebq.1).

  Every OTHER explain slot is plan-STRUCTURE (source/parent chains,
  compose lineage, merge rules, strict conflicts, tags, platforms, runner
  requirements) — author-published discovery metadata that is
  intentionally public per the threat model
  (`spec/015-Data-Classification.md`). See `egress/scrub-frame-value` for
  the runtime-vs-authored split rationale.

  NOTE on `:setup-order`/`:script-order`: the STEP STRUCTURE (which fx
  ids, in which order) is discovery metadata, but `substitute-args`
  injects resolved arg VALUES into the step payloads at plan-compile
  time, so the post-substitution sequences are value-bearing. The
  value-only redaction (`scrub-frame-value` replaces only leaves that
  EQUAL a declared-sensitive value) preserves the public step structure
  while redacting the embedded secrets."
  [:effective-args :args :substitutions :network :db-seed
   :sub-overrides :setup-order :script-order])

(defn- scrub-explain
  "Value-scrub the runtime/seeded VALUE slots of an `explain` map against
  `variant-id`'s frame declarations (rf2-12f2q) — on BOTH egress axes
  (rf2-9o5ixx, EP-0015 peer axes): a leaf equal to a declared-`:sensitive?`
  value becomes `:rf/redacted`, a leaf equal to a declared-`:large` value
  becomes the `:rf.size/large-elided` marker (sensitive wins where both
  apply). Plan-structure slots pass through untouched — they are
  author-published discovery metadata. `include?` opts out (the
  `--allow-sensitive-reads` + per-call escape hatch).

  Delegates to `egress/scrub-explain-values`, which collects candidate
  secrets from BOTH the live variant-frame app-db AND the plan's own
  `:db-seed` slot. The plan-`:db-seed` source is the FAIL-CLOSED pre-frame
  path (rf2-tag30h): `explain-variant` is a no-run path a caller can hit
  before any run allocates the frame, so a secret authored into `:db-seed`
  (and re-surfaced in `:effective-args` / `:network` / a step payload)
  must be value-matched against the plan's own seed, not just a live
  app-db that does not yet exist."
  [explain variant-id include?]
  (egress/scrub-explain-values explain variant-id explain-value-bearing-slots include?))

(defn tool-explain-variant
  "Docs: the variant-plan `:explain` projection for a variant — the SAME
  data the human Explain panel renders (spec/017 §Explain API). A thin
  mirror over the already-shipped `re-frame.story/explain` data API
  (rf2-ba86n.17): the single biggest agent↔human divergence today —
  humans have the Explain panel, agents had no MCP reach to it.

  The `:explain` map answers 'why did the plan resolve this way':
  `:source-chain` / `:parent-chain` (the `:extends` lineage),
  `:compose` (the resolved fragments / checks), `:strict-conflicts`
  (winning + losing sources + the rule that chose the winner), `:merge`
  (the per-field merge rules), `:args` / `:substitutions` /
  `:effective-args` (arg resolution), `:view-args-schema` /
  `:view-args-validation`, `:network` (per-route stubs + their lowered fx),
  `:sub-overrides` (+ fidelity), `:setup-order` / `:script-order`,
  `:checks` / `:assertions`, `:required-runner`, `:platforms`, `:tags`.

  Plan-derived data — no run, no live `:app-db` slice. BUT the plan
  RESOLVES author args into runtime VALUES: `:effective-args` /
  `:substitutions` / `:network` route replies / `:db-seed` /
  `:sub-overrides` override values / `:setup-order` + `:script-order` step
  payloads can carry a declared-sensitive value (a seeded token, a stubbed
  PII reply, a control-driven override, a setup-dispatched secret). Those
  value-bearing slots are value-redacted against the variant frame's
  declared-sensitive values at egress (rf2-12f2q, rf2-q8ebq.1) via
  `egress/scrub-frame-value` — the SAME value-based redaction the live
  tools apply to their derived trees. `:sub-overrides` /
  `:setup-order` / `:script-order` carry resolved arg VALUES (the SAME
  `substitute-args` that feeds `:substitutions`) so they are scrubbed too;
  the value-only redaction preserves their public step STRUCTURE. The
  remaining plan-STRUCTURE slots (`:source-chain` / `:parent-chain` /
  `:compose` / `:merge` / `:strict-conflicts` / `:tags` / …) are
  author-published discovery metadata and pass through unredacted.
  The `:extends`-resolved variant body is already public via
  `get-variant` / `variant->edn`; this adds the plan-compiler's
  source/merge/lowering reasoning on top. Pass `:include-sensitive true`
  to opt out (gated by `--allow-sensitive-reads`)."
  [args]
  (targs/with-variant args
    (fn [vk _body]
      (let [incl? (targs/include-sensitive? args)]
        (result/edn-result {:variant-id vk
                            :explain    (scrub-explain (story/explain vk) vk incl?)})))))

(defn tool-variant->edn
  "Docs: round-trippable EDN of a registered variant. Identical payload
  to `get-variant`; the text slot is the byte-stable `pr-str` EDN
  (keyword keys preserved) for agents that want strict EDN diffing.

  Emits a matching `:structuredContent` too (rf2-vyacl). The descriptor
  declares an `:outputSchema`; the official MCP SDK's high-level
  `callTool` REJECTS an outputSchema-declaring tool that returns no
  structuredContent with JSON-RPC -32600. (Same latent defect class as
  `get-story-instructions` — `variant->edn` was the only other tool
  still text-only.) The structured slot carries the same body map; the
  text slot remains the byte-stable EDN source of truth."
  [args]
  (targs/with-variant args
    (fn [_vk body]
      (result/edn-result body))))

(defn- md-h1 [s] (str "# " s "\n\n"))
(defn- md-h2 [s] (str "\n## " s "\n\n"))
(defn- md-h3 [s] (str "\n### " s "\n\n"))

(defn- md-kv-table
  "Render a small map as a GitHub-flavoured markdown `| key | value |` table.
  Empty maps render as a single em-dash so the section never reads as a
  visual hole. Values that are themselves maps / vectors / sets are
  `pr-str`-rendered — agents pasting this can re-parse them as EDN."
  [m]
  (if (empty? m)
    "—\n"
    (str "| key | value |\n|---|---|\n"
         (apply str
                (for [[k v] (sort-by str m)]
                  (str "| `" (pr-str k) "` | `" (pr-str v) "` |\n"))))))

(defn- md-bullet-list [xs]
  (if (empty? xs)
    "—\n"
    (apply str (for [x xs] (str "- `" (pr-str x) "`\n")))))

(defn- render-story-markdown
  "Project one story's registered body + its variant ids to a
  GitHub-flavoured markdown document. Suitable for agent-paste into
  an issue tracker or chat. The variant bodies are NOT inlined —
  they get summary entries; an agent that wants per-variant detail
  calls `get-variant` for the EDN form."
  [story-id story-body variant-ids]
  (str
    (md-h1 (str "Story `" story-id "`"))
    (when (:doc story-body)
      (str (:doc story-body) "\n"))
    (md-h2 "Default args")
    (md-kv-table (:args story-body))
    (md-h2 "Argument types")
    (md-kv-table (:argtypes story-body))
    (md-h2 "Tags")
    (md-bullet-list (sort (:tags story-body)))
    (md-h2 "Decorators")
    (md-bullet-list (:decorators story-body))
    (md-h2 "Variants")
    (if (seq variant-ids)
      (apply str
             (for [vid (sort variant-ids)
                   :let [vbody (story/handler-meta :variant vid)]]
               (str (md-h3 (str "`" vid "`"))
                    (when (:doc vbody)
                      (str (:doc vbody) "\n\n"))
                    "**Args**\n\n"
                    (md-kv-table (:args vbody))
                    "**Tags**\n\n"
                    (md-bullet-list (sort (:tags vbody))))))
      "—\n")))

(defn tool-get-docs-markdown
  "Docs: render a story's documentation as GitHub-flavoured Markdown
  (rf2-i0kyy). The existing `get-story` / `get-variant` tools return
  EDN — useful for programmatic consumption but not the right shape
  when an agent wants to paste a docs blurb into an issue tracker or
  chat. This tool composes story `:doc` + per-variant `:doc` + args /
  argtypes / tags / decorators into a single GFM string.

  Returns the markdown text both in the wire-canonical `:content`
  slot and as a `:markdown` structuredContent slot (paste-target for
  agent hosts that surface structured content)."
  [args]
  (targs/with-story-id args
    (fn [sk]
      (let [body     (story/handler-meta :story sk)
            variants (sort (story/variants-of sk))
            md       (render-story-markdown sk body variants)
            payload  {:story-id sk
                      :markdown md
                      :variants (vec variants)}]
        (result/text-result md payload)))))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Docs-category descriptors, in IMPL-SPEC §7.2 order."
  [{:name           "list-stories"
    :category       :docs
    :description    (str "All registered stories, optionally filtered by tags. Each entry carries id, doc, tags, and child variant ids. Paginated per rf2-76sf6 (`:limit` default 25, optional `:cursor` continuation). "
                         "A supplied `:tags` filter is always honoured: unknown tag names are dropped from the intersection and echoed back in an `:ignored-tags` slot — an unknown-only filter returns an empty `:stories` (never the full catalogue). "
                         "Examples: "
                         "1. All stories: {} -> {:stories [{:id :story.cart :doc \"...\" :tags [:dev :docs] :variants [:story.cart/empty :story.cart/full]} ...]}. "
                         "2. Filter by tag: {:tags [\":docs\"]} -> {:stories [...]} — only stories whose :tags intersect the requested set. "
                         "3. Filter by multiple tags (OR-intersect): {:tags [\":dev\" \":screenshot\"]} -> {:stories [...]} — any story matching either tag. "
                         "4. Unknown-only tag (typo / stale): {:tags [\":docz\"]} -> {:stories [] :ignored-tags [\":docz\"]} — empty, NOT the whole catalogue. "
                         "5. Mixed known+unknown: {:tags [\":docs\" \":docz\"]} -> {:stories [...] :ignored-tags [\":docz\"]} — the known tag filters, the unknown name is reported.")
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-pagination
                                  {:tags {:type "array" :items s/kw-or-string
                                          :description "Optional tag filter; story `:tags` set must intersect."}}))
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-list-stories}

   {:name           "get-story"
    :category       :docs
    :description    (str "Return one story's full body (`:doc`, `:component`, `:decorators`, `:args`, ... + its variant ids). "
                         "Examples: "
                         "1. Hit: {:story-id \":story.cart\"} -> {:id :story.cart :body {:doc \"...\" :component cart-view :args {...} :tags #{:dev}} :variants [:story.cart/empty :story.cart/full]}. "
                         "2. Bare-name form (no leading :): {:story-id \"story.cart\"} -> same hit. "
                         "3. Miss: {:story-id \":story.no/such\"} -> {:isError true :content [{:text \"Story not found: :story.no/such\"}]}.")
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:story-id s/kw-or-string})
                  :required ["story-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-get-story}

   {:name           "get-variant"
    :category       :docs
    :description    (str "Return one variant's full body (the resolved EDN, with `:extends` already applied at registration time). "
                         "Examples: "
                         "1. Hit: {:variant-id \":story.cart/full\"} -> {:id :story.cart/full :body {:doc \"...\" :args {:item-count 3} :script [...] :tags #{:dev}}}. "
                         "2. With extends already applied: {:variant-id \":story.cart/full-with-discount\"} -> {:body {... merged from :story.cart/full ...}}. "
                         "3. Miss: {:variant-id \":story.no/such\"} -> {:isError true :content [{:text \"Variant not found: :story.no/such\"}]}.")
    :typicalTokens  1000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-get-variant}

   {:name           "list-tags"
    :category       :docs
    :description    (str "Canonical tags + any custom tags registered by the project. The `:canonical` set is the bounded 12-entry vector — the seven spec/007 inclusion tags (`:dev :docs :test :screenshot :experimental :internal :agent`) plus the five rf2-k1k87 `:state/*` magnitude tags (`:state/empty :state/small :state/medium :state/large :state/special`). Paginated per rf2-76sf6 — `:canonical` stays full (bounded 12); `:custom` and `:all` slice per `:limit` / `:cursor`. "
                         "Examples: "
                         "1. Fresh registry: {} -> {:canonical [:agent :dev :docs :experimental :internal :screenshot :state/empty :state/large :state/medium :state/small :state/special :test] :custom [] :all [:agent :dev :docs ...]}. "
                         "2. Project with custom tags: {} -> {:canonical [...] :custom [:mobile :rtl] :all [:agent :dev :docs ... :mobile :rtl]}. "
                         "3. Pair with list-stories: call this first, then list-stories with :tags to filter the catalogue. "
                         "4. Large custom set — paginated: {:limit 25} -> {:canonical [...12...] :custom [...25...] :all [...37...] :total 50 :limit 25 :has-more? true :next-cursor \"<base64>\"}.")
    :typicalTokens  100
    :inputSchema    {:type "object"
                     :properties (s/with-max-tokens (s/with-pagination {}))
                     :additionalProperties false}
    :outputSchema   s/default-output-schema
    :annotations    s/read-only-annotations
    :handler        tool-list-tags}

   {:name           "list-modes"
    :category       :docs
    :description    (str "Registered modes (Chromatic-style saved tuples of args). Each entry is `{:id :doc :args}`. Paginated per rf2-76sf6 (`:limit` default 25, optional `:cursor`). "
                         "Examples: "
                         "1. Project with modes: {} -> {:modes [{:id :mode/dark :doc \"Dark theme\" :args {:theme :dark}} {:id :mode/mobile :doc \"...\" :args {:viewport :mobile}}]}. "
                         "2. Fresh registry (no project modes): {} -> {:modes []}. "
                         "3. Use with preview-variant: pass {:active-modes [\":mode/dark\"]} on a preview-variant call to render the variant under that mode. "
                         "4. Large mode set — paginated: {:limit 10} -> {:modes [...10...] :total 47 :limit 10 :has-more? true :next-cursor \"<base64>\"}.")
    :typicalTokens  200
    :inputSchema    {:type "object"
                     :properties (s/with-max-tokens (s/with-pagination {}))
                     :additionalProperties false}
    :outputSchema   s/default-output-schema
    :annotations    s/read-only-annotations
    :handler        tool-list-modes}

   {:name           "list-decorators"
    :category       :docs
    :description    (str "Read-only enumeration of registered decorators (rf2-mqp1u). Each entry carries "
                         "`:id`, `:kind`, `:doc` plus the kind-specific pure-data slots: `:has-wrap?` "
                         "for `:hiccup` decorators (the `:wrap` closure itself doesn't transport over "
                         "MCP); `:init` + `:app-db-patch` for `:frame-setup`; `:fx-id` + `:response` "
                         "for `:fx-override`. There is no decorator WRITE tool — decorators carry "
                         "closures JSON-RPC can't transport, so the enumeration is read-only. "
                         "Optional `:kind` arg narrows to one "
                         "decorator kind. Paginated per rf2-76sf6 (`:limit` default 25, optional "
                         "`:cursor`). "
                         "Examples: "
                         "1. All decorators: {} -> {:decorators [{:id :with-router :kind :hiccup :doc \"...\" :has-wrap? true} {:id :seed-cart :kind :frame-setup :doc \"...\" :init [...] :app-db-patch {...}} {:id :stub-http :kind :fx-override :fx-id :http :response {...}}]}. "
                         "2. Filter to one kind: {:kind \"fx-override\"} -> {:decorators [{:id :stub-http :kind :fx-override ...}]}. "
                         "3. Empty registry: {} -> {:decorators []}. "
                         "4. Paginated: {:limit 10} -> {:decorators [...10...] :total 23 :limit 10 :has-more? true :next-cursor \"<base64>\"}.")
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-pagination
                                  {:kind {:type "string"
                                          :description "Optional filter — only return decorators of this kind."
                                          :enum ["hiccup" "frame-setup" "fx-override"]}}))
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-list-decorators}

   {:name           "list-assertions"
    :category       :docs
    :description    (str "The eight canonical `:rf.assert/*` events with payload arity + semantics (the seven dispatched assertions plus the tape-evaluated `:rf.assert/schema-error`), PLUS `:registered` — the FULL assertion vocabulary the Story plan compiler accepts (`known-assertion-ids`): the eight canonical ids, the DOM family (`:rf.assert/dom-visible|dom-hidden|dom-text`), the visual / a11y oracles (`:rf.assert/visual-snapshot`, `:rf.assert/a11y`, `:rf.assert/a11y-structural`), and the reactive-count assertions (`:rf.assert/caused`, `:rf.assert/no-cascade-rerender`). The browser-tier ids (DOM / visual / a11y) require a richer runner — a headless run refuses them with `:cannot-run`, never a silent pass. Paginated per rf2-76sf6 — `:canonical` (the 8-entry doc vector) stays full; `:registered` slices per `:limit` / `:cursor`. "
                         "Examples: "
                         "1. Default: {} -> {:canonical [{:id :rf.assert/path-equals :payload \"[path expected]\" :semantics \"(= (get-in @app-db path) expected)\"} ...] :registered [:rf.assert/a11y :rf.assert/a11y-structural :rf.assert/caused :rf.assert/dispatched? :rf.assert/dom-hidden ...]}. "
                         "2. With budget knob: {:max-tokens 1000} -> same shape, tighter cap. "
                         "3. Pair with run-variant: discover the assertion vocab here, then write :script sequences referencing those event ids. "
                         "4. Paginated: {:limit 5} -> {:canonical [...8...] :registered [...5...] :total 16 :limit 5 :has-more? true :next-cursor \"<base64>\"}.")
    :typicalTokens  500
    :inputSchema    {:type "object"
                     :properties (s/with-max-tokens (s/with-pagination {}))
                     :additionalProperties false}
    :outputSchema   s/default-output-schema
    :annotations    s/read-only-annotations
    :handler        tool-list-assertions}

   {:name           "variant->edn"
    :category       :docs
    :description    (str "Round-trippable EDN of a registered variant. The text slot is the byte-stable pr-str EDN (keyword keys preserved); a matching :structuredContent carries the same body map. Use the text slot when you want byte-stable EDN for diffing. "
                         "Examples: "
                         "1. Hit: {:variant-id \":story.cart/full\"} -> {:doc \"...\" :args {:item-count 3} :tags #{:dev} :script [...]} (as pr-str EDN text). "
                         "2. Byte-stable for diffing two registries: same input always emits same text bytes (no JSON re-projection). "
                         "3. Miss: {:variant-id \":story.no/such\"} -> {:isError true :content [{:text \"Variant not found: :story.no/such\"}]}.")
    :typicalTokens  1000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-variant->edn}

   {:name           "explain-variant"
    :category       :docs
    :description    (str "The variant-plan `:explain` projection for a variant — the SAME data the human Explain panel renders (spec/017 §Explain API). Answers 'why did the plan resolve this way': the `:extends` source/parent chain, resolved `:compose` fragments/checks, `:strict-conflicts` (winning + losing sources + the deciding rule), the per-field `:merge` rules, `:args` / `:substitutions` / `:effective-args`, view-arg schema + validation, `:network` route stubs + their lowered fx, `:sub-overrides` + fidelity, the final `:setup-order` / `:script-order`, `:checks` / `:assertions`, `:required-runner`, `:platforms`, `:tags`. The plan-STRUCTURE slots are public discovery metadata, but the runtime-RESOLVED VALUE slots (`:effective-args` / `:args` / `:substitutions` / `:network` route replies / `:db-seed`) are value-scrubbed against the variant frame's frame declarations at egress on BOTH axes (rf2-12f2q, rf2-9o5ixx): a leaf equal to a declared-`:sensitive?` value becomes `:rf/redacted`, a leaf equal to a declared-`:large` value becomes the `:rf.size/large-elided` marker; pass `:include-sensitive true` to opt out (gated by --allow-sensitive-reads). The agent mirror of the human Explain panel (rf2-ba86n.17). "
                         "Examples: "
                         "1. Plain variant: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :explain {:source-chain [:story.cart/full] :parent-chain [] :compose [] :strict-conflicts [] :effective-args {...} :required-runner #{} ...}}. "
                         "2. Extends + compose: {:variant-id \":story.cart/full-with-discount\"} -> {:explain {:source-chain [:story.cart/full :story.cart/full-with-discount] :parent-chain [:story.cart/full] :compose [{:kind :fragment :id :frag/logged-in}] ...}}. "
                         "3. Miss: {:variant-id \":story.no/such\"} -> {:isError true :content [{:text \"Variant not found: :story.no/such\"}]}.")
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-include-sensitive {:variant-id s/kw-or-string}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-explain-variant}

   {:name           "get-docs-markdown"
    :category       :docs
    :description    (str "Render a story's documentation as GitHub-flavoured Markdown (rf2-i0kyy). "
                         "Composes the story `:doc` + per-variant `:doc` + args / argtypes / tags / "
                         "decorators into a single paste-ready string. The other docs tools "
                         "(`get-story`, `get-variant`, `variant->edn`) return EDN — useful for "
                         "programmatic consumption but not the right shape when an agent wants to drop "
                         "a docs blurb into an issue tracker or chat. The markdown is returned in the "
                         "wire-canonical `:content` text slot and as a `:markdown` structuredContent "
                         "slot for hosts that surface structured content separately. "
                         "Examples: "
                         "1. Hit: {:story-id \":story.cart\"} -> text body \"# Story `:story.cart`\\n...\" + structuredContent {:story-id :story.cart :markdown \"...\" :variants [...]}. "
                         "2. Story with no variants: {:story-id \":story.empty\"} -> text \"# Story `:story.empty`\\n\\n## Variants\\n\\n—\\n\". "
                         "3. Miss: {:story-id \":story.no/such\"} -> {:isError true :content [{:text \"Story not found: :story.no/such\"}]}.")
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:story-id s/kw-or-string})
                  :required ["story-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-get-docs-markdown}])
