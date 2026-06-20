(ns re-frame.mcp-conformance.wire-vocab.schemas
  "Canonical cross-MCP wire-marker schemas + the `canonical-markers`
  catalogue.

  This is the **contract data**, not boilerplate: a single Malli schema
  per cross-server wire marker, plus the ordered `canonical-markers`
  index binding each marker key to its schema, description, and
  per-server fixtures. The several focused test namespaces that share
  these schemas (`wire_vocab_test`, `cursor_stale_test`,
  `progress_notification_test`, …) each `:require` ONE support ns rather
  than re-deriving the shapes. The marker-family-LOCAL schemas
  (`ResultEnvelope`, `CascadeBundle`) stay co-located with their tests —
  only the shapes referenced across families live here.

  See `wire_vocab_test.clj`'s ns docstring for the full cross-MCP
  vocabulary rationale (six top-level wrapper markers + the
  `:rf.elision/at` fetch-handle tag, the per-server presence/absence
  contract, the source-text pins). This ns is the schemas only.

  Each schema is the cross-MCP-server contract for one wire marker. The
  marker is **always a single-key map** keyed by the reserved keyword;
  the value is a body conforming to the per-marker schema below. Agents
  pattern-match on the top-level reserved key — that rule is what makes
  the vocabulary cross-server."
  (:require [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Canonical schemas. Single source of truth.
;; ---------------------------------------------------------------------------

(def ReFrame2PairOverflowBody
  "The canonical `:rf.mcp/overflow` body shape (per
  `mcp-base/overflow.cljc/overflow-payload`). Every emit carries
  `:cap-tokens` + `:token-count` + `:tool` + `:hint` plus the `:limit
  :reached` sentinel. Required-not-optional — an emit missing any of
  these is a contract break, not a degenerate but tolerated marker.

  Multi-emitter: BOTH re-frame2-pair-mcp and story-mcp emit this marker
  via the shared `mcp-base.cap/apply-cap` → `overflow-payload` builder,
  so the body is byte-identical across both — there is exactly one
  shape, not a per-server variant. (story-mcp's wire-boundary cap is
  `tools/story-mcp/.../tools/wire_pipeline.cljc`, which calls
  `base-cap/apply-cap`. The name keeps the `ReFrame2Pair` prefix as the
  shape-author; the SHAPE is the cross-MCP contract.) Cap renames
  (`cap-tokens` vs `cap_tokens`), field renames (`hint` vs `next-step`),
  or empty bodies all trip the schema."
  [:map
   {:closed false}
   [:limit       [:enum :reached]]
   [:tool        [:or :string :keyword]]
   [:cap-tokens  :int]
   [:token-count :int]
   [:hint        [:or :string :keyword]]])

(def Overflow
  "`{:rf.mcp/overflow ReFrame2PairOverflowBody}` — the wrapper shape.

  CLOSED single-key map: a wire marker is ALWAYS a
  single reserved wrapper key (see the header — agents pattern-match on
  exactly one top-level key). A payload carrying the marker plus an
  unrelated sibling top-level key is a mixed-envelope emission a uniform
  cross-server client can't pattern-match; the `{:closed true}` arm
  rejects it so the conformance gate pins the single-key contract the
  contract documents. (The marker BODY stays open — additive body slots
  compose; it's the top-level envelope that is single-key.)"
  [:map {:closed true} [:rf.mcp/overflow ReFrame2PairOverflowBody]])

(defn summary-has-count-or-counts?
  "True when a map summary carries `:count` OR `:counts` (not neither).
  A map summary MUST report its cardinality one way or the other — the
  documented contract (`tools.cljs/tree-summary`) never emits a map
  marker that reports neither, and a downstream agent that can't read a
  count can't reason about the summarised collection's size."
  [{:keys [count counts]}]
  (or (some? count) (some? counts)))

(def SummaryBody
  "`{:rf.mcp/summary {...}}` body — the lazy tree-summary marker.

  Per re-frame2-pair-mcp `tools.cljs/tree-summary`. `:type` ∈ {:map :vector
  :set :seq :scalar}; map summaries carry `:keys` + `:count` +
  `:bytes`; vector/set/seq summaries carry `:count` + `:bytes`;
  scalar summaries carry `:value` + `:bytes`. Large maps add
  `:keys-truncated? true` when `:keys` is clamped to
  `summary-keys-cap` (re-frame2-pair-mcp pins 64; the spec doesn't pin).

  `:counts` (a per-top-key map) is permitted as an alternate
  per-key shape: a single MAP marker MUST carry `:count` or `:counts`,
  not neither. It is a permitted schema variant so a future MCP server
  shipping per-key counts validates without a schema bump.

  ## Type-specific shape

  The schema dispatches on `:type` and enforces the documented per-type
  required slots. It does not mark every shape-slot `{:optional true}`
  with no type predicate — were it to, `{:type :map :bytes 1}` (a map
  summary carrying neither `:keys` nor a count) and `{:type :scalar
  :bytes 1}` (a scalar with no `:value`) would both validate: unusable
  markers a future server could ship while this gate stayed green. Each
  arm is CLOSED to the documented slot set, so a scalar
  carrying `:keys`, or a vector carrying `:value`, is now rejected too
  (a cross-type slot leak is a contract break, not a tolerated extra).

  - `:map`              — `:type` + `:bytes` + `:keys` + (`:count` OR
                          `:counts`); optional `:keys-truncated?`.
  - `:vector`/`:set`/`:seq` — `:type` + `:bytes` + `:count`.
  - `:scalar`           — `:type` + `:bytes` + `:value`."
  [:multi {:dispatch :type}
   [:map
    [:and
     [:map
      {:closed true}
      [:type [:= :map]]
      [:bytes :int]                                           ;; pr-str char count
      [:keys [:sequential :any]]                              ;; required for maps
      [:keys-truncated? {:optional true} :boolean]            ;; only when clamped
      [:count  {:optional true} :int]                         ;; cardinality (scalar form)
      [:counts {:optional true} [:map-of :any :int]]]         ;; per-key map variant
     [:fn summary-has-count-or-counts?]]]
   [:vector
    [:map
     {:closed true}
     [:type [:= :vector]]
     [:bytes :int]
     [:count :int]]]
   [:set
    [:map
     {:closed true}
     [:type [:= :set]]
     [:bytes :int]
     [:count :int]]]
   [:seq
    [:map
     {:closed true}
     [:type [:= :seq]]
     [:bytes :int]
     [:count :int]]]
   [:scalar
    [:map
     {:closed true}
     [:type [:= :scalar]]
     [:bytes :int]
     [:value :any]]]])

(def Summary
  "`{:rf.mcp/summary SummaryBody}` — the wrapper shape. CLOSED single-key
  map (see `Overflow`)."
  [:map {:closed true} [:rf.mcp/summary SummaryBody]])

(def root-cache-id-name
  "The de-dupe library's canonical root cache-id, as a bare name string
  (no `:` / symbol-quote prefix). `de-dupe.core/expand` ALWAYS begins
  reconstruction at `(make-cache-element 0)` = `de-dupe.cache/cache-0`,
  and `create-cache-internal` ALWAYS assocs that key as the root entry
  (day8/de-dupe v0.3.0 `core.cljc:253-255` + `:132`). A cache map with no
  `cache-0` entry is therefore un-expandable — the Node-side decoder
  (`tools/mcp-conformance/lib/dedup-envelope.cjs` `ROOT_CACHE_ID`) throws
  on exactly that shape. Pinned here so the JVM schema and the Node
  decoder agree on the required root."
  "de-dupe.cache/cache-0")

(defn dedup-cache-root-key?
  "True when `k` names the de-dupe root cache element
  (`de-dupe.cache/cache-0`). Accepts BOTH wire representations of the
  same key:

  - the EDN/CLJS form `de-dupe.core/de-dupe-eq` actually emits — a
    namespaced SYMBOL `de-dupe.cache/cache-0` (`make-cache-element` is
    `(symbol ...)`); and
  - the namespaced-KEYWORD form `:de-dupe.cache/cache-0` the JVM
    wire-vocab fixtures author it as.

  `(name k)`/`(namespace k)` are identical across symbol and keyword,
  so one predicate covers both. A string key (the JSON-on-the-wire form
  the Node decoder sees) is matched verbatim — that path is exercised by
  the live JVM↔Node agreement test below."
  [k]
  (or (= root-cache-id-name k)
      (and (or (keyword? k) (symbol? k))
           (= "de-dupe.cache" (namespace k))
           (= "cache-0"       (name k)))))

(defn has-dedup-root?
  "True when `cache` is a non-empty map carrying the canonical
  `de-dupe.cache/cache-0` root entry (in symbol, keyword, or string
  form). This is the load-bearing structural invariant: an
  agent/client decoder calls `de-dupe.core/expand`, which starts at the
  root — a table with no root is un-expandable and the Node decoder
  rejects it. `{}` and a root-less cache both fail here."
  [cache]
  (and (map? cache)
       (boolean (some dedup-cache-root-key? (keys cache)))))

(def DedupTable
  "`{:rf.mcp/dedup-table <flat-cache>}` — the structural-dedup wrapper.

  The body is the day8/de-dupe cache map. re-frame2-pair-mcp's and
  story-mcp's actual caches key it by the de-dupe library's namespaced
  symbols `de-dupe.cache/cache-N` (the wire-vocab fixtures author the
  same keys in keyword form). The load-bearing claim is the top-level
  `:rf.mcp/dedup-table` marker key AND that the value is a cache map
  the agent host can actually expand — i.e. it MUST carry the canonical
  `de-dupe.cache/cache-0` ROOT entry. `de-dupe.core/expand` begins
  reconstruction at that root (day8/de-dupe v0.3.0 `core.cljc:132`); a
  cache with no root is un-expandable and the Node-side decoder
  (`lib/dedup-envelope.cjs`) throws on it.

  The `has-dedup-root?` predicate requires that root entry so the JVM
  contract is not looser than the client-visible one: an empty `{}` and
  any root-less cache (which the Node decoder rejects) are rejected
  here too, so both encodings agree on the accepted cache shape.

  CLOSED single-key map (see `Overflow`)."
  [:map {:closed true} [:rf.mcp/dedup-table [:and :map [:fn has-dedup-root?]]]])

(def DiffFromBody
  "An epoch's `:db-after` slot, diff-encoded against `:db-before` and
  projected into path-headed cluster sections.

  Per `re-frame.mcp-base.diff-encode/diff-encode-db-after` and
  re-frame2-pair-mcp Principles §\"Cross-MCP vocabulary\". Shape:

      {:rf.mcp/diff-from :db-before
       :sections [{:section-path [<key>...]
                   :section-kind :added|:removed|:modified
                   :patches      [[<path> :assoc <new-value>]
                                  [<path> :dissoc]
                                  ...]}
                  ...]}

  The `:rf.mcp/diff-from` value is a keyword naming the
  diff-against slot — `:db-before` is the only conformant value
  today (an epoch's `:db-after` diff-encodes against the SAME
  record's `:db-before`).

  Each section heads N patches with a breadcrumb path + a kind
  summary. The agent reads `:section-path` + `:section-kind` for
  cluster intent; decoding flattens sections' `:patches` and
  replays them via `apply-patches` to reconstruct `:db-after`.

  The body slot is `:sections` (the path-headed cluster projection
  above); the marker key `:rf.mcp/diff-from` heads it.

  CLOSED map: unlike the other markers,
  `:rf.mcp/diff-from` is NOT a single-key wrapper — the marker key and
  its `:sections` body slot ride as TWO top-level keys (the documented
  shape above; `diff-encode-db-after` emits exactly these two). Closing
  the map to that pair rejects any OTHER sibling top-level key — a
  mixed-envelope `:db-after` that smuggled an extra key past the gate.
  The marker SHAPE is the framework's diff projection, so the closed
  top-level pair IS the wire contract here."
  [:map
   {:closed true}
   [:rf.mcp/diff-from [:enum :db-before]]
   [:sections [:sequential
               [:map
                [:section-path [:vector :any]]
                [:section-kind [:enum :added :removed :modified]]
                [:patches [:sequential
                           [:or
                            [:tuple [:vector :any] [:= :assoc] :any]
                            [:tuple [:vector :any] [:= :dissoc]]]]]]]]])

(def ElisionMarkerBody
  "`{:rf.size/large-elided {...}}` body — the size-elision marker.

  Normative shape per spec/Spec-Schemas.md §`:rf/elision-marker`.
  `:digest` is optional (only when `:rf.size/include-digests? true`
  per spec/API.md `rf/elide-wire-value`)."
  [:map
   [:path    [:vector :any]]
   [:bytes   :int]
   [:type    [:enum :map :vector :set :scalar :string]]
   [:reason  [:enum :frame :marks]]
   [:hint    [:maybe :string]]
   [:handle  [:tuple [:= :rf.elision/at] [:vector :any]]]
   [:digest  {:optional true} :string]])

(def ElisionMarker
  "`{:rf.size/large-elided ElisionMarkerBody}` — the wrapper shape. CLOSED
  single-key map (see `Overflow`)."
  [:map {:closed true} [:rf.size/large-elided ElisionMarkerBody]])

(def CacheHitBody
  "`{:rf.mcp/cache-hit {...}}` body — per-session response-cache hit
  marker. Per `mcp-base/vocab.cljc/cache-hit-key` and re-frame2-pair-mcp
  `cache.cljs/cache-hit-payload`. The agent host correlates by `:hash`
  and re-uses the prior `tools/call` payload for the same `(tool,
  args)` pair — the marker itself is content-free (no fresh state
  observed since `:unchanged-since`).

  `:via` distinguishes the two hit paths: `:result-hash`
  (match-after-eval) ran the tool and discovered the hash matched;
  `:precheck` short-circuited the eval entirely. Same
  vocabulary, different cost saved.

  Single-server today (re-frame2-pair-mcp); the `:rf.mcp/*` namespace reserves
  it cross-MCP per Conventions §Reserved namespaces — a future MCP
  server adopting a session cache ships the same shape."
  [:map
   {:closed false}
   [:hash            [:or :int :string]]
   [:unchanged-since :int]
   [:tool            [:or :string :keyword]]
   [:via             [:enum :result-hash :precheck]]
   [:hint            [:or :string :keyword]]])

(def CacheHit
  "`{:rf.mcp/cache-hit CacheHitBody}` — the wrapper shape. CLOSED single-key
  map (see `Overflow`)."
  [:map {:closed true} [:rf.mcp/cache-hit CacheHitBody]])

(def ReFrame2PairProgressNotificationParams
  "Canonical `notifications/progress` params shape for re-frame2-pair-mcp's
  `subscribe` streaming tool (per `tools/subscribe.cljs/
  progress-payload`).

  The MCP envelope shape is:

      {:method \"notifications/progress\"
       :params {:progressToken <opaque>          ;; echoed from caller's _meta
                :progress      <tick :int>        ;; monotonically increasing
                :message       <edn-string>       ;; EDN-printed batch
                :_meta        {:data {:dropped-events  :int
                                      :dropped-bytes   :int
                                      :overflow-reason [:maybe :string]}}}}

  `:dropped-events` / `:dropped-bytes` ride non-negative; the runtime
  emits them on every tick (zero values are valid — the slot is the
  load-bearing shape). `:overflow-reason` is the `pr-str` of the
  runtime sentinel keyword (`:max-buffered-events` /
  `:max-buffered-bytes`) or null when no overflow tripped
  this tick.

  Pinned cross-server today as a re-frame2-pair-mcp-only shape; if a future
  MCP server ships a `subscribe`/`unsubscribe` pair (per NAMING.md),
  its emit MUST satisfy this schema or extend it as a `[:or ...]`
  (same posture as `ReFrame2PairOverflowBody`)."
  [:map
   {:closed false}
   [:progressToken :any]                          ;; opaque per MCP spec
   [:progress      :int]
   [:message       :string]
   [:_meta
    [:map
     {:closed false}
     [:data
      [:map
       {:closed false}
       [:dropped-events nat-int?]
       [:dropped-bytes  nat-int?]
       [:overflow-reason [:maybe :string]]]]]]])

(def CursorStaleResult
  "Structured error-result envelope where `:reason :rf.mcp/cursor-stale`
  signals a cursor no longer addresses a valid position. The canonical
  `:reason` value + the cross-MCP envelope builder live in
  `mcp-base/vocab.cljc/cursor-stale-reason` +
  `mcp-base/cursor.cljc/cursor-stale-result`.

  Unlike the other markers in this file, `:rf.mcp/cursor-stale` is NOT
  a top-level wrapper — it rides as the `:reason` value on a generic
  `{:ok? false ...}` error envelope. Agents pattern-match on the
  `:reason` keyword to either drop the cursor and restart, or widen
  the window and retry. The cross-server conformance contract pins
  the `:reason` keyword + the `:ok? false` posture; envelope-specific
  slots (`:requested-id`, `:head-id`, `:tool`, `:hint`) are open
  per-server.

  ## MULTI-server reason value

  cursor-stale is emitted by BOTH MCP servers, for two distinct
  staleness causes that share the same agent recovery vocabulary:

  - re-frame2-pair-mcp — the cursor's epoch-id has rotated out of the
    bounded epoch RING (`tools/cursor.cljs/cursor-stale-result`).
  - story-mcp — the underlying registry's id-set CHANGED between
    cursor-mint and cursor-deref, so the offset/total/sig cursor no
    longer addresses the same page
    (`tools/story-mcp/.../tools/cursor.cljc/cursor-stale-result`).

  Both builders delegate to the shared
  `mcp-base/cursor.cljc/cursor-stale-result`, which sources the
  `:reason` from `vocab/cursor-stale-reason` — so an agent that learned
  the drop-and-restart path on one server reuses it on the other. The
  story-mcp builder wraps that data-map in story-mcp's MCP wire
  envelope (`{:content … :isError true :structuredContent <this shape>}`)
  — so it is the `:structuredContent` slot of story-mcp's emission that
  this schema validates (see `story-cursor-stale-emitted-live-by-canonical-builder`
  below). It is a multi-server reason-value contract: story-mcp emits it
  for cursor pagination on the Docs `list-*` tools."
  [:map
   {:closed false}
   [:ok?    [:enum false]]
   [:reason [:enum :rf.mcp/cursor-stale]]])

;; ---------------------------------------------------------------------------
;; Envelope indicator-field slots (`:dropped-sensitive` / `:elided-large`).
;; Per Conventions §Cross-MCP indicator-field vocabulary and
;; Spec 009 §Size elision in traces — Indicator field on tool responses
;; (MUST-level). Unqualified keys riding the tool's own envelope; integer
;; counters; omit when zero. These are NOT cross-server wire MARKERS
;; (those are the `:rf.mcp/*` / `:rf.size/*` namespaced shapes above) —
;; they are scalar envelope-level summaries of how many walker
;; suppressions happened per call. The conformance contract: both slots
;; ride alongside every tool that walks a tree-typed payload, the keys
;; are unqualified (no namespace), and the values are non-negative
;; integers.
;; ---------------------------------------------------------------------------

(def DroppedSensitive
  "`{... :dropped-sensitive N}` envelope slot — integer count of leaves
  the walker dropped because they matched `:sensitive? true`. Open map
  so it composes with any tool's envelope shape; the load-bearing
  claim is the slot KEY (`:dropped-sensitive`, unqualified) and the
  value's `nat-int?` type."
  [:map {:closed false} [:dropped-sensitive nat-int?]])

(def ElidedLarge
  "`{... :elided-large N}` envelope slot — integer count of leaves the
  walker replaced with the `:rf.size/large-elided` marker. Same
  shape contract as `:dropped-sensitive`."
  [:map {:closed false} [:elided-large nat-int?]])

;; ---------------------------------------------------------------------------
;; Canonical-marker index. The ordered set of cross-MCP markers; each
;; entry binds the schema, a description, and the per-server fixtures.
;; A new marker landing in the cross-server vocabulary MUST add an
;; entry here.
;; ---------------------------------------------------------------------------

(def canonical-markers
  "Ordered table of cross-server wire markers under conformance.

  Each entry pins:
  - `:key`       — the reserved top-level keyword
  - `:schema`    — the canonical Malli schema (wrapper shape)
  - `:fixtures`  — per-server example values (must all validate)
  - `:servers`   — set of servers that emit/spec the marker today

  The conformance assertion: every fixture in `:fixtures` validates
  against `:schema`; the source/spec text of every server in
  `:servers` mentions the marker key literally."
  [{:key      :rf.mcp/overflow
    :schema   Overflow
    ;; Multi-server marker as of rf2-yxgcsz — story-mcp's wire-boundary
    ;; cap landed (`tools/story-mcp/.../tools/wire_pipeline.cljc` calls
    ;; `base-cap/apply-cap`), so BOTH servers emit `:rf.mcp/overflow`
    ;; via the shared `mcp-base.cap/apply-cap` → `overflow/overflow-payload`
    ;; builder. The body is byte-identical across both — same posture as
    ;; `:rf.mcp/dedup-table` (rf2-90eft). Both servers source the marker
    ;; key from `re-frame.mcp-base.vocab/overflow-key` so the literal
    ;; stays byte-identical; an agent that learned the overflow retry
    ;; signal on either server recognises it on the other.
    :servers  #{:re-frame2-pair-mcp :story-mcp}
    :fixtures {:re-frame2-pair-mcp {:rf.mcp/overflow
                           {:limit       :reached
                            :token-count 12400
                            :cap-tokens  5000
                            :tool        "snapshot"
                            :hint        "Narrow scope: pass `path [:k1 :k2]` to slice ..."}}
               :re-frame2-pair-mcp-keyword-hint {:rf.mcp/overflow
                                        {:limit       :reached
                                         :token-count 12400
                                         :cap-tokens  5000
                                         :tool        "snapshot"
                                         :hint        :narrow-scope}}
               ;; story-mcp emission (rf2-yxgcsz) — the `run-variant`
               ;; payload (`:app-db` + `:rendered-hiccup` + `:snapshot`)
               ;; can blow the cap; `wire_pipeline/invoke-tool` replaces it
               ;; via `base-cap/apply-cap`, whose `build-overflow-result`
               ;; surfaces the `overflow/overflow-payload` marker as the
               ;; `:structuredContent` slot. The body is the SAME shape the
               ;; shared builder emits — the fixture pins that a regression
               ;; in story-mcp's cap-pipeline wiring trips this gate. The
               ;; per-tool `:hint` is story-mcp's own (`run-variant` table).
               :story-mcp {:rf.mcp/overflow
                           {:limit       :reached
                            :token-count 9300
                            :cap-tokens  5000
                            :tool        "run-variant"
                            :hint        "Tighten scope: pass `:cell-overrides` to shrink the run, or omit `:active-modes`. The :app-db / :rendered-hiccup slots dominate; raise `max-tokens` (0 disables) if the full payload is genuinely needed."}}}}

   {:key      :rf.mcp/summary
    :schema   Summary
    :servers  #{:re-frame2-pair-mcp}
    :fixtures {:re-frame2-pair-mcp-map     {:rf.mcp/summary
                                   {:type  :map
                                    :keys  [:user :cart :ui]
                                    :count 3
                                    :bytes 1200}}
               :re-frame2-pair-mcp-vector  {:rf.mcp/summary
                                   {:type  :vector
                                    :count 50
                                    :bytes 900}}
               :re-frame2-pair-mcp-set     {:rf.mcp/summary
                                   {:type  :set
                                    :count 12
                                    :bytes 80}}
               :re-frame2-pair-mcp-seq     {:rf.mcp/summary
                                   {:type  :seq
                                    :count 17
                                    :bytes 110}}
               :re-frame2-pair-mcp-scalar  {:rf.mcp/summary
                                   {:type  :scalar
                                    :value 42
                                    :bytes 2}}
               :re-frame2-pair-mcp-clamped {:rf.mcp/summary
                                   {:type             :map
                                    :keys             (vec (range 64))
                                    :count            128
                                    :bytes            5000
                                    :keys-truncated?  true}}
               ;; Per-key counts variant — schema permits :counts
               ;; alongside :count; pinned with a re-frame2-pair-shaped fixture
               ;; so the alternate slot stays schema-validated even
               ;; without a second server contributing one.
               :re-frame2-pair-mcp-counts  {:rf.mcp/summary
                                   {:type   :map
                                    :keys   [:cart :user :ui]
                                    :counts {:cart 47 :user 3 :ui 12}
                                    :bytes  12400}}}}

   {:key      :rf.mcp/dedup-table
    :schema   DedupTable
    ;; Multi-server marker as of rf2-90eft — story-mcp adopted the
    ;; same wire-boundary structural-dedup transform (mirror of pair-
    ;; mcp's rf2-obpa9). Both servers source the marker key from
    ;; `re-frame.mcp-base.vocab/dedup-table-key` so the literal stays
    ;; byte-identical; an agent that learned the slot on either server
    ;; reconstructs identically via `de-dupe.core/expand`.
    :servers  #{:re-frame2-pair-mcp :story-mcp}
    :fixtures {:re-frame2-pair-mcp         {:rf.mcp/dedup-table
                                   {:de-dupe.cache/cache-0 [:de-dupe.cache/cache-1 :de-dupe.cache/cache-1]
                                    :de-dupe.cache/cache-1 {:event-id :foo :handler-id :bar}}}
               ;; rf2-x0pr0 — the integer-keyed fixture
               ;; (`{1 {...} 2 {...}}`) was REMOVED. It was fiction: the
               ;; day8/de-dupe library ALWAYS keys the cache by the
               ;; namespaced symbol `de-dupe.cache/cache-N` (root
               ;; `cache-0`) — never integers (v0.3.0 `core.cljc`
               ;; `make-cache-element` / `create-cache-internal`). A
               ;; root-less integer-keyed table has no `cache-0` entry,
               ;; so the Node-side decoder (`lib/dedup-envelope.cjs`)
               ;; THROWS on it — the exact JVM-loose / Node-strict
               ;; disagreement this gate now closes. The
               ;; `DedupTable`-rejects-malformed negative tests below
               ;; pin that the schema refuses it.
               ;; story-mcp emission (rf2-90eft) — the `run-variant`
               ;; payload re-keys the same `:app-db` value into
               ;; `:rendered-hiccup` and `:snapshot`; dedup collapses
               ;; those into a single cache slot referenced thrice. The
               ;; fixture pins that exact shape so a regression in the
               ;; cap-pipeline wiring (`tools/story-mcp/src/.../cap.cljc
               ;; apply-dedup`) trips this gate.
               :story-mcp        {:rf.mcp/dedup-table
                                   {:de-dupe.cache/cache-0 {:frame :story.cart/full
                                                            :app-db :de-dupe.cache/cache-1
                                                            :rendered-hiccup [:div.cart {:data-state :de-dupe.cache/cache-1}]
                                                            :snapshot {:body :de-dupe.cache/cache-1}}
                                    :de-dupe.cache/cache-1 {:cart {:items [] :total 0}}}}}}

   {:key      :rf.mcp/diff-from
    ;; rf2-voux7 finding 2 — use the CLOSED `DiffFromBody` schema (the
    ;; same schema the live-emission gate validates the real encoder
    ;; output against) rather than the pre-fix loose
    ;; `[:map [:rf.mcp/diff-from ...] [:sections :any]]`, which accepted
    ;; ANY `:sections` value AND any extra sibling top-level key.
    :schema   DiffFromBody
    ;; re-frame2-pair-mcp specs / emits today. The schema and the marker are
    ;; reserved in the cross-MCP family per re-frame2-pair-mcp Principles §
    ;; \"Cross-MCP vocabulary\". The body slot is the
    ;; sections-per-cluster projection (rf2-qeous) — same
    ;; `:rf.mcp/diff-from` marker key, new `:sections` body.
    :servers  #{:re-frame2-pair-mcp}
    :fixtures {:re-frame2-pair-mcp {:rf.mcp/diff-from :db-before
                           :sections [{:section-path [:cart :items]
                                       :section-kind :modified
                                       :patches      [[[:cart :items] :assoc [{:sku "abc"}]]]}
                                      {:section-path [:tmp]
                                       :section-kind :removed
                                       :patches      [[[:tmp] :dissoc]]}]}}}

   {:key      :rf.size/large-elided
    :schema   ElisionMarker
    ;; Reserved by Conventions / spec; re-frame2-pair-mcp emits today. The
    ;; `:reason` slot carries the declaration provenance per EP-0015 §8
    ;; (rf2-d2r3um): the large declaration is now FRAME-OWNED (`:source
    ;; :frame` — `re-frame.frame-classification/install!`), so `:reason`
    ;; is `:frame` for a frame-declared slot, or `:marks` for an
    ;; imperative `add-marks`/`set-marks` declaration (`re-frame.marks`).
    ;; The pre-EP-0015 `:schema` nomination path is gone. Three fixtures
    ;; below cover both `:reason` variants × two body shapes
    ;; (string-with-hint / map-with-digest / set-via-marks).
    :servers  #{:re-frame2-pair-mcp}
    :fixtures {:re-frame2-pair-mcp-frame-string
               {:rf.size/large-elided
                {:path   [:user :uploaded-pdf]
                 :bytes  102400
                 :type   :string
                 :reason :frame
                 :hint   "User-uploaded PDF; fetch via get-path."
                 :handle [:rf.elision/at [:user :uploaded-pdf]]}}
               :re-frame2-pair-mcp-frame-with-digest
               {:rf.size/large-elided
                {:path   [:cofx :db]
                 :bytes  524288
                 :type   :map
                 :reason :frame
                 :hint   nil
                 :handle [:rf.elision/at [:cofx :db]]
                 :digest "sha256:deadbeefcafef00d"}}
               :re-frame2-pair-mcp-marks-set
               {:rf.size/large-elided
                {:path   [:scratch :payload]
                 :bytes  262144
                 :type   :set
                 :reason :marks
                 :hint   "Imperatively marked via set-marks; fetch via get-path."
                 :handle [:rf.elision/at [:scratch :payload]]}}}}

   {:key      :rf.mcp/cache-hit
    :schema   CacheHit
    ;; re-frame2-pair-mcp emits today (rf2-3rt1f result-hash + rf2-36xod precheck
    ;; paths in `cache.cljs/cache-hit-payload`). The literal lives in
    ;; `mcp-base/vocab.cljc` as `cache-hit-key`, where every cross-MCP
    ;; marker is canonicalised. Per rf2-i3ffz F-GAP-4.
    :servers  #{:re-frame2-pair-mcp}
    :fixtures {:re-frame2-pair-mcp-result-hash
               {:rf.mcp/cache-hit
                {:hash            -1234567890
                 :unchanged-since 1715760000000
                 :tool            "snapshot"
                 :via             :result-hash
                 :hint            "Payload byte-identical to the prior tools/call ..."}}
               :re-frame2-pair-mcp-precheck
               {:rf.mcp/cache-hit
                {:hash            42
                 :unchanged-since 1715760123456
                 :tool            "watch-epochs"
                 :via             :precheck
                 :hint            "Pre-eval cache hit (rf2-36xod) — state unchanged."}}}}])
