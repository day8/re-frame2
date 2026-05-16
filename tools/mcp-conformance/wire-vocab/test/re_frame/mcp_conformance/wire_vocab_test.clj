(ns re-frame.mcp-conformance.wire-vocab-test
  "Cross-MCP wire-vocabulary conformance (rf2-j2z7o).

  Three MCP servers ship under `tools/`: pair2-mcp, story-mcp, and
  causa-mcp (spec-only today). The first and the third share a
  reserved cross-server **wire vocabulary** — namespaced map keys an
  agent recognises identically across every server.

  Five top-level markers, plus the `:rf.elision/at` fetch-handle tag
  (embedded inside the `:rf.size/large-elided` body's `:handle` slot —
  not a standalone marker; pinned via the elision-marker body schema):

  - `:rf.mcp/overflow`      — token-budget overflow marker
                              (causa-mcp Principles mechanism 1,
                               pair2-mcp `tools.cljs` `overflow-payload`)
  - `:rf.mcp/summary`       — tree-summary lazy-mode marker
                              (causa-mcp Principles mechanism 4,
                               pair2-mcp `tools.cljs` `tree-summary`)
  - `:rf.mcp/dedup-table`   — structural-dedup wrapper
                              (causa-mcp Principles mechanism 5,
                               pair2-mcp `tools.cljs` `dedup-value`)
  - `:rf.mcp/diff-from`     — diff-encoded `:db-after` marker
                              (pair2-mcp `tools.cljs`
                               `diff-encode-db-after`; cross-MCP
                               vocabulary per pair2-mcp Principles
                               §\"Cross-MCP vocabulary\")
  - `:rf.size/large-elided` — size-elision wire marker
                              (spec/Spec-Schemas §`:rf/elision-marker`,
                               pair2-mcp Principles §\"Size-elision\")
  - `:rf.elision/at`        — size-elision fetch-handle tag, embedded
                              inside the `:rf.size/large-elided` body's
                              `:handle` slot per `ElisionMarkerBody`
                              (NOT a standalone top-level marker)

  story-mcp does NOT currently emit any of these markers — it operates
  on small, structured story/variant metadata and stays under the
  wire-cap by construction. The story-mcp `tools/*.cljc` files namespace
  their own vocabulary under `:rf.story/*`, `:rf.assert/*`, `:rf.error/*`
  per its own spec. The vocabulary becomes relevant on story-mcp the
  day a tool starts returning bulk runtime state; until then the
  conformance gate guards the *contract*: when story-mcp adopts a
  marker it MUST use the canonical shape, not invent a near-miss.

  ## What this test guards

  1. **One canonical schema per marker.** A single Malli schema lives
     here. Every fixture EDN representing a server's actual emission
     shape MUST validate against the canonical schema.

  2. **Per-server fixture parity.** Each marker has a `pair2-mcp` and
     `causa-mcp` fixture. Both validate against the same schema —
     that's the cross-server conformance assertion. Divergent shapes
     fail loud.

  3. **Source-text vocabulary pin.** A grep against each server's
     source (pair2-mcp `src/`) and spec (causa-mcp `spec/`) asserts
     the canonical literal appears AND no near-miss variant (e.g.
     `:rf.mcp/overflows`, `:rf.mcp/dedup_table`, the underscore
     form) appears. A rename in either server surfaces here.

  4. **Cross-server presence/absence.** The set of markers each
     server is contracted to emit is pinned. Adding a sixth marker
     requires editing this test — which forces the conformance
     contract to stay in sync with the spec.

  ## Why pure JVM Clojure (not Node SDK)

  The sibling `tools/mcp-conformance/test/end-to-end-*.cjs` files
  drive each server through the official MCP SDK client (handshake
  + tools/list + tools/call against a live process). That validates
  *protocol* conformance.

  This test validates *vocabulary* conformance — the shapes of EDN
  values the server emits as response payloads. It does NOT need a
  live server: the schemas are normative, the fixtures are authored
  from each server's spec/source, and the grep step pins those
  authored fixtures to the actual source/spec text.

  ## causa-mcp impl gap

  causa-mcp's implementation has not landed yet (its `tools/` entry
  is `README.md` + `spec/`, no `src/`). The fixtures and the spec
  grep step against `tools/causa-mcp/spec/Principles.md` +
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` cover the vocabulary
  today; when impl lands, a follow-up bead extends this test to
  grep `tools/causa-mcp/src/` and add live-emission fixtures if
  their shape diverges from the spec snippets."
  (:require [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [malli.core      :as m]
            [malli.error     :as me]
            [re-frame.mcp-conformance.fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; Repo-root + slurp helpers live in `re-frame.mcp-conformance.fixtures`
;; (rf2-113ti). Shared across the three conformance test namespaces in
;; this artefact.
;; ---------------------------------------------------------------------------

;; `strip-comments-and-strings` lives in
;; `re-frame.mcp-conformance.fixtures` (rf2-rto1l) — promoted from a
;; private defn here so all three conformance test namespaces can share
;; the same documentation-vs-emission discrimination logic.

;; ---------------------------------------------------------------------------
;; Canonical schemas. Single source of truth.
;;
;; Each schema is the cross-MCP-server contract for one wire marker.
;; The marker is **always a single-key map** keyed by the reserved
;; keyword; the value is a body conforming to the per-marker schema
;; below. Agents pattern-match on the top-level reserved key — that
;; rule is what makes the vocabulary cross-server.
;; ---------------------------------------------------------------------------

(def Pair2OverflowBody
  "pair2-mcp's `:rf.mcp/overflow` body shape (per
  `mcp-base/overflow.cljc/overflow-payload`). Every emit carries
  `:cap-tokens` + `:token-count` + `:tool` + `:hint` plus the `:limit
  :reached` sentinel. Required-not-optional — an emit missing any of
  these is a contract break, not a degenerate but tolerated marker."
  [:map
   {:closed false}
   [:limit       [:enum :reached]]
   [:tool        [:or :string :keyword]]
   [:cap-tokens  :int]
   [:token-count :int]
   [:hint        [:or :string :keyword]]])

(def CausaOverflowBody
  "causa-mcp's `:rf.mcp/overflow` body shape (per
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §\"1. Token budget cap\").
  Every emit carries `:cap` + `:would-be` + `:hint` plus the `:limit
  :reached` sentinel; `:continuation` is optional and only present when
  the server can emit a resumable cursor."
  [:map
   {:closed false}
   [:limit        [:enum :reached]]
   [:cap          :int]
   [:would-be     :int]
   [:hint         [:or :string :keyword]]
   [:continuation {:optional true}
    [:map
     [:cursor    {:optional true} [:or :string :nil]]
     [:next-args {:optional true} [:maybe :map]]]]])

(def OverflowBody
  "`{:rf.mcp/overflow {...}}` body — the token-budget overflow marker.

  Per causa-mcp `004-Wire-Pipeline.md` §\"1. Token budget cap\" and
  pair2-mcp `mcp-base/overflow.cljc/overflow-payload`. The cross-server
  contract pins TWO shapes, NOT one open map:

  - **pair2-mcp shape:** `:limit :reached` + `:cap-tokens` +
    `:token-count` + `:tool` + `:hint`. Hyphen-separated child names;
    `:cap-tokens` is the cap, `:token-count` is the over-budget count.
  - **causa-mcp shape:** `:limit :reached` + `:cap` + `:would-be` +
    `:hint` (+ optional `:continuation` `:cursor` / `:next-args`).
    Same posture, server-local naming.

  An emit shaped as `{:rf.mcp/overflow {:limit :reached}}` alone is
  NOT conformant — every required field on at least one of the two
  shapes MUST be present. Cap renames (`cap-tokens` vs `cap_tokens`),
  field renames (`hint` vs `next-step`), or empty bodies all trip the
  schema. Per rf2-kn8cj (refactor-audit r2 of rf2-azk9c §F-VOCAB-2)."
  [:or Pair2OverflowBody CausaOverflowBody])

(def Overflow
  "`{:rf.mcp/overflow OverflowBody}` — the wrapper shape."
  [:map [:rf.mcp/overflow OverflowBody]])

(def SummaryBody
  "`{:rf.mcp/summary {...}}` body — the lazy tree-summary marker.

  Per causa-mcp Principles §\"4. Lazy summary\" and pair2-mcp
  `tools.cljs/tree-summary`. `:type` ∈ {:map :vector :set :seq
  :scalar}; map summaries carry `:keys` + `:count` + `:bytes`;
  vector/set/seq summaries carry `:count` + `:bytes`; scalar
  summaries carry `:value` + `:bytes`. Large maps add
  `:keys-truncated? true` when `:keys` is clamped to
  `summary-keys-cap` (pair2-mcp pins 64; the spec doesn't pin).

  causa-mcp's Principles example uses `:counts` (a per-top-key
  map) instead of `:count`. The schema permits both — but a single
  marker MUST carry one or the other, not neither."
  [:map
   {:closed false}
   [:type [:enum :map :vector :set :seq :scalar]]
   [:bytes :int]                                               ;; pr-str char count
   [:keys             {:optional true} [:sequential :any]]     ;; maps only
   [:keys-truncated?  {:optional true} :boolean]               ;; maps only when clamped
   [:count            {:optional true} :int]                   ;; non-scalars
   [:counts           {:optional true} [:map-of :any :int]]    ;; causa-mcp variant
   [:value            {:optional true} :any]])                 ;; scalars

(def Summary
  "`{:rf.mcp/summary SummaryBody}` — the wrapper shape."
  [:map [:rf.mcp/summary SummaryBody]])

(def DedupTable
  "`{:rf.mcp/dedup-table <flat-cache>}` — the structural-dedup wrapper.

  The body is the day8/de-dupe cache map: `{:de-dupe.cache/cache-0
  <root> :de-dupe.cache/cache-N <subtree> ...}`. causa-mcp's
  Principles example uses integer keys (`{1 {...} 2 {...}}`) for
  illustration; pair2-mcp's actual cache uses the de-dupe library's
  namespaced-keyword form. The schema permits either — the
  load-bearing claim is the top-level `:rf.mcp/dedup-table` marker
  key and that the value is a *map* (the agent host calls
  `de-dupe.core/expand` on it)."
  [:map [:rf.mcp/dedup-table :map]])

(def DiffFromBody
  "An epoch's `:db-after` slot, diff-encoded against `:db-before`.

  Per pair2-mcp `tools.cljs/diff-encode-db-after` and pair2-mcp
  Principles §\"Cross-MCP vocabulary\". Shape:

      {:rf.mcp/diff-from :db-before
       :patches [[<path> :assoc <new-value>]
                 [<path> :dissoc]
                 ...]}

  The `:rf.mcp/diff-from` value is a keyword naming the
  diff-against slot — `:db-before` is the only conformant value
  today (an epoch's `:db-after` diff-encodes against the SAME
  record's `:db-before`)."
  [:map
   [:rf.mcp/diff-from [:enum :db-before]]
   [:patches [:sequential
              [:or
               [:tuple [:vector :any] [:= :assoc] :any]
               [:tuple [:vector :any] [:= :dissoc]]]]]])

(def ElisionMarkerBody
  "`{:rf.size/large-elided {...}}` body — the size-elision marker.

  Normative shape per spec/Spec-Schemas.md §`:rf/elision-marker`.
  `:digest` is optional (only when `:rf.size/include-digests? true`
  per spec/API.md `rf/elide-wire-value`)."
  [:map
   [:path    [:vector :any]]
   [:bytes   :int]
   [:type    [:enum :map :vector :set :scalar :string]]
   [:reason  [:enum :schema]]
   [:hint    [:maybe :string]]
   [:handle  [:tuple [:= :rf.elision/at] [:vector :any]]]
   [:digest  {:optional true} :string]])

(def ElisionMarker
  "`{:rf.size/large-elided ElisionMarkerBody}` — the wrapper shape."
  [:map [:rf.size/large-elided ElisionMarkerBody]])

(def CacheHitBody
  "`{:rf.mcp/cache-hit {...}}` body — per-session response-cache hit
  marker. Per `mcp-base/vocab.cljc/cache-hit-key` and pair2-mcp
  `cache.cljs/cache-hit-payload`. The agent host correlates by `:hash`
  and re-uses the prior `tools/call` payload for the same `(tool,
  args)` pair — the marker itself is content-free (no fresh state
  observed since `:unchanged-since`).

  `:via` distinguishes the two hit paths: `:result-hash` (rf2-3rt1f
  match-after-eval) ran the tool and discovered the hash matched;
  `:precheck` (rf2-36xod) short-circuited the eval entirely. Same
  vocabulary, different cost saved.

  Single-server today (pair2-mcp); the `:rf.mcp/*` namespace reserves
  it cross-MCP per Conventions §Reserved namespaces — when causa-mcp
  grows a session cache it ships the same shape."
  [:map
   {:closed false}
   [:hash            [:or :int :string]]
   [:unchanged-since :int]
   [:tool            [:or :string :keyword]]
   [:via             [:enum :result-hash :precheck]]
   [:hint            [:or :string :keyword]]])

(def CacheHit
  "`{:rf.mcp/cache-hit CacheHitBody}` — the wrapper shape."
  [:map [:rf.mcp/cache-hit CacheHitBody]])

(def Pair2ProgressNotificationParams
  "Canonical `notifications/progress` params shape for pair2-mcp's
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
  `:max-buffered-bytes` per rf2-ho4ve) or null when no overflow tripped
  this tick.

  Pinned cross-server today as a pair2-mcp-only shape: causa-mcp ships
  the same streaming pair (`subscribe`/`unsubscribe`) under NAMING.md
  via `tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/subscribe.cljs`.
  causa-mcp's `subscribe` emit MUST satisfy this schema or extend it as
  a `[:or ...]` (same posture as OverflowBody)."
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
  signals the cursor's epoch-id is no longer in the runtime ring (per
  `mcp-base/vocab.cljc/cursor-stale-reason` and pair2-mcp
  `tools/cursor.cljs/cursor-stale-result`).

  Unlike the other markers in this file, `:rf.mcp/cursor-stale` is NOT
  a top-level wrapper — it rides as the `:reason` value on a generic
  `{:ok? false ...}` error envelope. Agents pattern-match on the
  `:reason` keyword to either drop the cursor and restart, or widen
  the window and retry. The cross-server conformance contract pins
  the `:reason` keyword + the `:ok? false` posture; envelope-specific
  slots (`:requested-id`, `:head-id`, `:tool`, `:hint`) are open
  per-server.

  Single-server today (pair2-mcp); causa-mcp's spec reserves the same
  reason value for its planned pagination surface."
  [:map
   {:closed false}
   [:ok?    [:enum false]]
   [:reason [:enum :rf.mcp/cursor-stale]]])

;; ---------------------------------------------------------------------------
;; Envelope indicator-field slots (`:dropped-sensitive` / `:elided-large`).
;; Per Conventions §Cross-MCP indicator-field vocabulary (rf2-2499j) and
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
    :servers  #{:pair2-mcp :causa-mcp}                         ;; not :story-mcp
    :fixtures {:pair2-mcp {:rf.mcp/overflow
                           {:limit       :reached
                            :token-count 12400
                            :cap-tokens  5000
                            :tool        "snapshot"
                            :hint        "Narrow scope: pass `path [:k1 :k2]` to slice ..."}}
               :causa-mcp {:rf.mcp/overflow
                           {:limit        :reached
                            :cap          5000
                            :would-be     12400
                            :hint         :switch-mode
                            :continuation {:cursor    "opaque-cursor-123"
                                           :next-args {:mode :sample}}}}}}

   {:key      :rf.mcp/summary
    :schema   Summary
    :servers  #{:pair2-mcp :causa-mcp}
    :fixtures {:pair2-mcp-map     {:rf.mcp/summary
                                   {:type  :map
                                    :keys  [:user :cart :ui]
                                    :count 3
                                    :bytes 1200}}
               :pair2-mcp-vector  {:rf.mcp/summary
                                   {:type  :vector
                                    :count 50
                                    :bytes 900}}
               :pair2-mcp-set     {:rf.mcp/summary
                                   {:type  :set
                                    :count 12
                                    :bytes 80}}
               :pair2-mcp-seq     {:rf.mcp/summary
                                   {:type  :seq
                                    :count 17
                                    :bytes 110}}
               :pair2-mcp-scalar  {:rf.mcp/summary
                                   {:type  :scalar
                                    :value 42
                                    :bytes 2}}
               :pair2-mcp-clamped {:rf.mcp/summary
                                   {:type             :map
                                    :keys             (vec (range 64))
                                    :count            128
                                    :bytes            5000
                                    :keys-truncated?  true}}
               :causa-mcp-map     {:rf.mcp/summary
                                   {:type   :map
                                    :keys   [:cart :user :ui]
                                    :counts {:cart 47 :user 3 :ui 12}
                                    :bytes  12400}}}}

   {:key      :rf.mcp/dedup-table
    :schema   DedupTable
    :servers  #{:pair2-mcp :causa-mcp}
    :fixtures {:pair2-mcp  {:rf.mcp/dedup-table
                            {:de-dupe.cache/cache-0 [:de-dupe.cache/cache-1 :de-dupe.cache/cache-1]
                             :de-dupe.cache/cache-1 {:event-id :foo :handler-id :bar}}}
               ;; causa-mcp's Principles uses integer-keyed example for
               ;; brevity; both forms validate against the schema.
               :causa-mcp  {:rf.mcp/dedup-table
                            {1 {:event-id :foo :handler-id :bar}
                             2 {:event-id :baz}}}}}

   {:key      :rf.mcp/diff-from
    :schema   [:map [:rf.mcp/diff-from [:enum :db-before]] [:patches :any]]
    ;; pair2-mcp specs / emits today. The schema and the marker are
    ;; reserved in the cross-MCP family per pair2-mcp Principles §
    ;; \"Cross-MCP vocabulary\" — causa-mcp's spec mentions it via the
    ;; same family but the example shape is pair2-mcp-owned.
    :servers  #{:pair2-mcp}
    :fixtures {:pair2-mcp {:rf.mcp/diff-from :db-before
                           :patches          [[[:cart :items] :assoc [{:sku "abc"}]]
                                              [[:tmp]          :dissoc]]}}}

   {:key      :rf.size/large-elided
    :schema   ElisionMarker
    ;; Reserved by Conventions / spec; pair2-mcp emits today and
    ;; causa-mcp's Principles cross-links the canonical shape from
    ;; its §"Streaming over batch" trimmer. Two fixtures below
    ;; represent two distinct emission shapes — schema/string vs
    ;; schema/map-with-digest (the schema-driven nomination path is
    ;; the only nomination path post Path-D / rf2-w3n5u).
    :servers  #{:pair2-mcp :causa-mcp}
    :fixtures {:pair2-mcp-schema-string
               {:rf.size/large-elided
                {:path   [:user :uploaded-pdf]
                 :bytes  102400
                 :type   :string
                 :reason :schema
                 :hint   "User-uploaded PDF; fetch via get-path."
                 :handle [:rf.elision/at [:user :uploaded-pdf]]}}
               :pair2-mcp-schema-with-digest
               {:rf.size/large-elided
                {:path   [:cofx :db]
                 :bytes  524288
                 :type   :map
                 :reason :schema
                 :hint   nil
                 :handle [:rf.elision/at [:cofx :db]]
                 :digest "sha256:deadbeefcafef00d"}}}}

   {:key      :rf.mcp/cache-hit
    :schema   CacheHit
    ;; pair2-mcp emits today (rf2-3rt1f result-hash + rf2-36xod precheck
    ;; paths in `cache.cljs/cache-hit-payload`). The literal lives in
    ;; `mcp-base/vocab.cljc` as `cache-hit-key`, where every cross-MCP
    ;; marker is canonicalised; causa-mcp's spec mentions the marker
    ;; family but has no impl. Per rf2-i3ffz F-GAP-4.
    :servers  #{:pair2-mcp}
    :fixtures {:pair2-mcp-result-hash
               {:rf.mcp/cache-hit
                {:hash            -1234567890
                 :unchanged-since 1715760000000
                 :tool            "snapshot"
                 :via             :result-hash
                 :hint            "Payload byte-identical to the prior tools/call ..."}}
               :pair2-mcp-precheck
               {:rf.mcp/cache-hit
                {:hash            42
                 :unchanged-since 1715760123456
                 :tool            "watch-epochs"
                 :via             :precheck
                 :hint            "Pre-eval cache hit (rf2-36xod) — state unchanged."}}}}])

;; ---------------------------------------------------------------------------
;; Fixture conformance — every authored fixture validates against the
;; canonical schema for its marker. This is the primary cross-server
;; assertion: pair2-mcp's and causa-mcp's fixture shapes BOTH conform
;; to the same single schema.
;; ---------------------------------------------------------------------------

(deftest every-fixture-conforms-to-its-canonical-schema
  (doseq [{:keys [key schema fixtures]} canonical-markers
          [fixture-name fixture-value] fixtures]
    (testing (str "marker " key " — fixture " fixture-name)
      (is (m/validate schema fixture-value)
          (str "Fixture " fixture-name " for " key
               " failed schema validation:\n"
               (me/humanize (m/explain schema fixture-value)))))))

(deftest every-canonical-marker-has-at-least-two-fixtures
  ;; Cross-server is at minimum a 2-fixture story (pair2 + causa, or
  ;; pair2-A + pair2-B for shape variants like map-summary vs
  ;; vector-summary). One fixture means \"only one server emits this\"
  ;; — which we still allow when the :servers set is a singleton.
  (doseq [{:keys [key fixtures servers]} canonical-markers]
    (testing (str "marker " key " — fixture count")
      (let [n (count fixtures)]
        (if (= 1 (count servers))
          (is (>= n 1)
              (str key " is single-server (" servers
                   ") so >=1 fixture suffices, got " n))
          (is (>= n 2)
              (str key " is multi-server (" servers
                   ") so >=2 fixtures required, got " n)))))))

(deftest overflow-empty-body-is-rejected
  ;; rf2-kn8cj (refactor-audit r2 of rf2-azk9c §F-VOCAB-2): the previous
  ;; `OverflowBody` schema marked every slot except `:limit` `{:optional
  ;; true}`, so an emit shaped as `{:rf.mcp/overflow {:limit :reached}}`
  ;; alone validated. That under-constrained the cross-server contract:
  ;; an emit MUST carry either pair2-mcp's shape (`:cap-tokens` +
  ;; `:token-count` + `:tool` + `:hint`) OR causa-mcp's shape (`:cap` +
  ;; `:would-be` + `:hint` + optional `:continuation`). The schema now
  ;; encodes both as a `[:or ...]` of required-field shapes; this gate
  ;; pins the regression directly.
  (testing "empty body (only :limit :reached) fails validation"
    (is (not (m/validate Overflow {:rf.mcp/overflow {:limit :reached}}))
        "Overflow schema must reject an emit with only :limit :reached — both pair2 and causa shapes require more fields."))
  (testing "missing-required-pair2 fields fail validation"
    ;; pair2 shape lacks :token-count
    (is (not (m/validate Overflow
                         {:rf.mcp/overflow
                          {:limit      :reached
                           :tool       "snapshot"
                           :cap-tokens 5000
                           :hint       "..."}}))
        "pair2-shape emit missing :token-count must fail"))
  (testing "missing-required-causa fields fail validation"
    ;; causa shape lacks :would-be
    (is (not (m/validate Overflow
                         {:rf.mcp/overflow
                          {:limit :reached
                           :cap   5000
                           :hint  :switch-mode}}))
        "causa-shape emit missing :would-be must fail"))
  (testing "hybrid (cherry-picking fields across shapes) fails"
    ;; Mixing pair2 :cap-tokens with causa :would-be — under-specified
    ;; for both shapes; should not validate.
    (is (not (m/validate Overflow
                         {:rf.mcp/overflow
                          {:limit      :reached
                           :cap-tokens 5000
                           :would-be   12400
                           :hint       "..."}}))
        "hybrid (cap-tokens + would-be) emit must fail")))

;; ---------------------------------------------------------------------------
;; Source-text vocabulary pin. The literal marker key MUST appear in
;; each contracted server's source/spec; near-miss variants (snake_case,
;; pluralised, mis-pluralised) MUST NOT appear. A rename in the
;; framework or in either server surfaces here — the schema is one
;; gate, the literal-occurrence pin is the second.
;;
;; The pin is split in two (rf2-vj8y3, refactor-audit r2 of rf2-azk9c
;; §F-VOCAB-1 + F-VOCAB-3):
;;
;; - **emit-sources** — source files where the literal MUST appear as
;;   actual data (not in a comment or docstring). Stripped via
;;   `strip-comments-and-strings` before the grep. A rename in any of
;;   these files MUST trip the test even if a docstring elsewhere still
;;   carries the old form.
;; - **doc-sources** — spec docs and prose-y descriptors where the
;;   literal SHOULD appear for human readers. Looser — a `str/includes?`
;;   against raw text suffices; documentation reorganisation may move
;;   the mention around without tripping the gate.
;;
;; The pre-rf2-vj8y3 pin grepped `tools.cljs` + `Principles.md` +
;; `003-Tool-Catalogue.md` with `some`, which passed because the spec
;; docs prose-referenced every marker — even though four of five
;; literals did not appear in any pair2-mcp source code AT ALL (they
;; were imported via `re-frame.mcp-base.vocab/<key>`). A rename inside
;; `mcp-base/vocab.cljc` (the canonical home of every literal) didn't
;; trip the gate. The new emit-side pin closes that hole.
;; ---------------------------------------------------------------------------

(def ^:private emit-source-files
  "Per-server source files where the marker literal MUST appear as
  DATA (not in a docstring/comment). The literal is `pr-str`'d on a
  per-marker basis and grepped against the file's text AFTER
  `strip-comments-and-strings` has neutered docstring/comment mentions.

  For pair2-mcp the canonical literal home is `mcp-base/vocab.cljc` —
  every wire marker keyword is declared once there (`overflow-key`,
  `summary-key`, `dedup-table-key`, `diff-from-key`,
  `large-elided-key`) and pair2-mcp consumes the symbol, not the
  literal. A rename to ANY of those `def` values trips this pin
  regardless of which pair2-mcp tool source emits the marker — which
  is the right invariant; emit-sites that import from vocab.cljc
  cannot drift independently of the canonical declaration.

  causa-mcp has no `src/` today — the marker literals only appear in
  its spec text. That's the doc-source gate's job; the emit-source set
  is empty until impl lands."
  {:pair2-mcp ["tools/mcp-base/src/re_frame/mcp_base/vocab.cljc"]
   :causa-mcp []
   :story-mcp []})

(def ^:private doc-source-files
  "Per-server prose-y sources where the marker literal SHOULD appear
  for human readers (specs, descriptors, catalogues). Looser
  match — a raw `str/includes?` suffices; docs may rearrange prose
  without tripping the gate. Drift here means the docs lag, not that
  the emit broke."
  {:pair2-mcp ["tools/pair2-mcp/spec/Principles.md"
               "tools/pair2-mcp/spec/003-Tool-Catalogue.md"]
   :causa-mcp ["tools/causa-mcp/spec/Principles.md"
               "tools/causa-mcp/spec/004-Wire-Pipeline.md"
               "tools/causa-mcp/spec/DESIGN-RATIONALE.md"]
   :story-mcp []})

(def ^:private all-source-files
  "Union of emit-sources and doc-sources, by server. Used by the
  near-miss anti-pin: we want to forbid near-miss spellings anywhere
  in any conformance-tracked file, not just emit-sites."
  (merge-with into emit-source-files doc-source-files))

(defn- marker-key->literal
  "Render a marker key as the literal string that MUST appear in the
  source. The renderer prints with the `:` prefix and the full
  namespaced form — that's what `clojure.core/pr-str` emits and what
  the source files use verbatim."
  [k]
  (pr-str k))

(defn- near-miss-variants
  "Generate near-miss spellings of a marker keyword. A rename to any
  of these forms MUST NOT slip through. We check:
  - snake_case form  (`:rf.mcp/dedup_table`)
  - pluralised tail  (`:rf.mcp/overflows`)
  - all-lowercase ns (`:rf.mcp/Overflow` -> none; we already are
                      lowercase, so this variant is irrelevant for
                      these markers; included for future-proofing)
  - underscore-in-ns (`:rf_mcp/overflow`)
  The list is conservative — false positives here would block
  legitimate text in surrounding docs."
  [k]
  (let [s (pr-str k)
        ns* (namespace k)
        nm  (name k)]
    (cond-> []
      (str/includes? nm "-")
      (conj (str ":" ns* "/" (str/replace nm #"-" "_"))) ;; snake_case
      (str/includes? ns* ".")
      (conj (str ":" (str/replace ns* #"\." "_") "/" nm)) ;; ns dots -> underscores
      true
      (into [(str s "s")                                   ;; pluralised
             (str s "?")]))))                              ;; predicate form

(deftest marker-literal-appears-in-every-contracted-server-emit-source
  ;; The load-bearing pin: every marker literal each server is
  ;; contracted to emit MUST appear as DATA (not docstring/comment) in
  ;; at least one of the registered emit-source files. The
  ;; `strip-comments-and-strings` walker is applied before the grep so
  ;; a rename inside the canonical declaration site (pair2-mcp's
  ;; `mcp-base/vocab.cljc`) trips the gate even if old docstrings still
  ;; mention the prior name.
  ;;
  ;; story-mcp emits zero markers today — its servers entry is empty in
  ;; `canonical-markers/:servers`, so this loop never iterates over it.
  ;; causa-mcp's emit-source set is empty until `tools/causa-mcp/src/`
  ;; lands; the `is (seq emit-files)` assertion fires loud when it
  ;; does, forcing the reviewer to extend `emit-source-files`.
  (doseq [{:keys [key servers]} canonical-markers
          server                servers]
    (testing (str "marker " key " literal in " server " emit-sources")
      (let [literal    (marker-key->literal key)
            emit-files (get emit-source-files server)
            doc-files  (get doc-source-files server)]
        ;; A server with zero emit-sources AND zero doc-sources is a
        ;; gap — either impl-not-landed (causa-mcp before src/ lands;
        ;; the `causa-mcp-impl-still-absent` deftest in the sibling
        ;; namespaces covers that posture explicitly) or a missing
        ;; catalogue entry the reviewer must add.
        (is (or (seq emit-files) (seq doc-files))
            (str "No emit-sources or doc-sources registered for "
                 server " — extend `emit-source-files` or "
                 "`doc-source-files`."))
        (cond
          ;; pair2-mcp / impl-landed path: emit-sources MUST carry the
          ;; literal as data after comment/string stripping.
          (seq emit-files)
          (is (some (fn [rel]
                      (let [stripped (fx/strip-comments-and-strings
                                       (fx/read-source rel))]
                        (str/includes? stripped literal)))
                    emit-files)
              (str "Literal " literal
                   " missing from " server " EMIT-sources " emit-files
                   " (checked AFTER stripping docstrings/comments). "
                   "If the canonical declaration moved, update "
                   "`emit-source-files`."))

          ;; causa-mcp / impl-not-landed path: spec-text coverage only.
          ;; Doc-sources are the looser pin — raw `str/includes?`.
          :else
          (is (some (fn [rel]
                      (str/includes? (fx/read-source rel) literal))
                    doc-files)
              (str "Literal " literal " missing from " server
                   " DOC-sources " doc-files
                   ". (No emit-sources registered; spec-text coverage "
                   "is the impl-not-landed stand-in.)")))))))

(deftest marker-literal-appears-in-pair2-mcp-doc-sources
  ;; Defence-in-depth: pair2-mcp's spec/descriptor docs SHOULD also
  ;; carry each emitted-marker literal for human readers. Looser pin —
  ;; raw `str/includes?` allows docstring mentions; the load-bearing
  ;; check is the emit-source pin above. Drift here means the docs
  ;; lag, not that the emit shape broke.
  (doseq [{:keys [key servers]} canonical-markers
          :when                 (contains? servers :pair2-mcp)]
    (testing (str "marker " key " literal in pair2-mcp doc-sources")
      (let [literal   (marker-key->literal key)
            doc-files (get doc-source-files :pair2-mcp)]
        (is (some (fn [rel]
                    (str/includes? (fx/read-source rel) literal))
                  doc-files)
            (str "Literal " literal
                 " missing from pair2-mcp doc-sources " doc-files
                 ". The docs may have re-organised the prose; either "
                 "restore the mention or update `doc-source-files`."))))))

(deftest no-near-miss-variants-appear-in-any-server-source
  ;; Defence-in-depth: a rename to a near-miss form (e.g. snake_case)
  ;; would slip past the literal-presence test if the canonical form
  ;; ALSO still appears somewhere. This test makes sure no near-miss
  ;; co-exists alongside the canonical — across BOTH emit-sources AND
  ;; doc-sources (drift in either is a vocabulary-drift bug).
  (doseq [{:keys [key]} canonical-markers
          [server files] all-source-files
          variant       (near-miss-variants key)
          rel           files]
    (testing (str server " — " rel " — near-miss " variant)
      (is (not (str/includes? (fx/read-source rel) variant))
          (str "Found near-miss variant " variant " for " key
               " in " server "/" rel
               " — this is a vocabulary-drift bug. The canonical "
               "form is " (marker-key->literal key))))))

;; ---------------------------------------------------------------------------
;; JS-vs-Malli `OverflowBody` cross-encoding sanity (rf2-0zqox).
;;
;; `test/live-pair2-overflow.cjs` hand-rolls `assertOverflowBody` as a JS
;; re-encoding of `Pair2OverflowBody`. The two encodings must agree on
;; the same contract — that's the whole point of pinning a vocabulary
;; conformance gate; a drift between the encodings is a vocabulary bug
;; (a marker shape the Malli side considers valid that the JS side
;; rejects, or vice versa). Before this gate landed, divergence could
;; ship silently: a tightening to `Pair2OverflowBody` (e.g. promoting
;; `:cap-tokens` from optional to required, which rf2-kn8cj just did)
;; could pass the Malli side while the JS side hadn't been updated.
;;
;; The gate works by slurping the JS file and grepping for every Malli
;; required-field substring. A field added to the Malli schema MUST
;; appear in the JS form's hand-rolled assertions; missing a field
;; trips this gate. Drift in the OTHER direction (JS has a check the
;; Malli schema doesn't) is handled by the Malli schema's reject set
;; in `overflow-empty-body-is-rejected` — together the two gates pin
;; the cross-encoding contract from both sides.
;;
;; Why grep, not parse-and-execute: pulling a JS parser onto the JVM
;; classpath to evaluate `assertOverflowBody` against a fixture would
;; be ~50× the dependency surface for a pin that's a five-field union
;; today. The grep set is a curated whitelist — adding a Malli field
;; means adding one entry here; the friction is correct.
;; ---------------------------------------------------------------------------

(def ^:private live-pair2-overflow-js-rel
  "Relative path to the hand-rolled JS assertion. Single source of truth
  — drift here surfaces against the slurp below."
  "tools/mcp-conformance/test/live-pair2-overflow.cjs")

(def ^:private pair2-overflow-js-required-grep-markers
  "Substrings the JS `assertOverflowBody` MUST contain to pin every
  required field on `Pair2OverflowBody`. Each entry is `[malli-field
  js-substring]` — the field for error reporting, the substring as the
  grep target. A field added to `Pair2OverflowBody` MUST add a row
  here; a field removed from `Pair2OverflowBody` MUST remove a row.
  Drift surfaces as a test failure naming the missing field.

  Per rf2-i3ffz F-CORR-2/F-HYG-4 (`live-pair2-overflow.cjs` rewrite
  around `edn-data` + a data-driven `REQUIRED_FIELDS` table): the JS
  side now parses EDN keywords as bare strings (no `:` prefix) and the
  required-field assertions live in one table rather than five typeof
  branches. The grep targets pin each row of that table by its literal
  appearance in the source — a `REQUIRED_FIELDS` row that's been
  renamed or deleted trips this gate even if the data-driven loop
  silently skips it."
  [;; The `[<field>, <pred>, <desc>]` row signatures in REQUIRED_FIELDS.
   ;; Each row is on its own source line so a substring search uniquely
   ;; pins the row's presence; whitespace inside the row is normalised
   ;; in the source for alignment but `str/includes?` is whitespace-
   ;; sensitive so we pin the canonical spaced form.
   [":limit :reached"
    "['limit',       (v) => v === 'reached',          'enum :reached']"]
   [":cap-tokens : int"
    "['cap-tokens',  (v) => typeof v === 'number',    'int']"]
   [":token-count : int"
    "['token-count', (v) => typeof v === 'number',    'int']"]
   [":tool : string|keyword"
    "['tool',        (v) => typeof v === 'string',    'string|keyword']"]
   [":hint : string|keyword"
    "['hint',        (v) => typeof v === 'string',    'string|keyword']"]
   ;; Cross-field invariant: a tripped cap MUST report token-count
   ;; STRICTLY GREATER THAN cap-tokens. The JS form pins this as a
   ;; numeric comparison after the per-field loop; the Malli schema
   ;; doesn't model cross-field relationships, so this grep is the only
   ;; gate on the invariant. A future regression that emitted a
   ;; degenerate overflow with `:token-count == :cap-tokens` would trip
   ;; here.
   ["token-count > cap-tokens invariant"
    "body['token-count'] <= body['cap-tokens']"]])

(deftest js-assertOverflowBody-pins-every-pair2-overflow-required-field
  ;; The cross-encoding sanity gate. For every required field on the
  ;; Malli `Pair2OverflowBody` schema, the JS `assertOverflowBody`
  ;; function MUST carry a substring that asserts the same shape.
  ;; Missing fields trip this gate with the field name in the error.
  (let [js-src (fx/read-source live-pair2-overflow-js-rel)]
    (doseq [[field grep-pattern] pair2-overflow-js-required-grep-markers]
      (testing (str "JS assertOverflowBody pins field " field)
        (is (str/includes? js-src grep-pattern)
            (str "Field `" field
                 "` (Malli `Pair2OverflowBody`) is not pinned by the "
                 "JS `assertOverflowBody` in " live-pair2-overflow-js-rel
                 ". Looked for substring: " (pr-str grep-pattern)
                 ".\nIf you tightened `Pair2OverflowBody`, mirror the "
                 "change in the JS assertion; if you loosened it, "
                 "remove the entry from "
                 "`pair2-overflow-js-required-grep-markers`."))))))

;; ---------------------------------------------------------------------------
;; Server-coverage pin. The set of servers each marker is contracted
;; against is the *current* state; this test prints it on `--verbose`
;; so a reviewer sees the shape. It also asserts the only servers we
;; reference are the three known servers — a typo in a `:servers` set
;; surfaces here.
;; ---------------------------------------------------------------------------

(deftest server-references-are-all-known
  (doseq [{:keys [key servers]} canonical-markers]
    (testing (str "marker " key " — :servers values")
      (is (every? fx/known-servers servers)
          (str "Unknown server in :servers for " key ": "
               (remove fx/known-servers servers))))))

(deftest story-mcp-still-emits-zero-cross-mcp-markers
  ;; Self-documenting tripwire: the day story-mcp adopts ANY of the
  ;; cross-MCP markers as an INLINE EMISSION, this test flips RED —
  ;; at which point the reviewer adds story-mcp to the `:servers` set
  ;; on the affected marker, adds a fixture, and extends
  ;; `server-source-files`. That's the right friction; conformance is
  ;; not free.
  ;;
  ;; Comment- and docstring-only mentions are stripped before the
  ;; check (via `strip-comments-and-strings`). story-mcp re-uses
  ;; mcp-base's overflow / elision machinery; its `tools/*.cljc` files
  ;; document `:rf.mcp/overflow` and `:rf.size/large-elided` in
  ;; docstrings without inline-emitting either. Documentation is not
  ;; an emission — this tripwire fires only on bare-code occurrences
  ;; (rf2-xx42k).
  (let [story-files ["tools/story-mcp/src/re_frame/story_mcp/protocol.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/cap.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/registry.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/helpers.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/schemas.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/dev.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/docs.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/testing.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/write.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/recorder.cljc"]]
    (doseq [{:keys [key]} canonical-markers
            rel           story-files]
      (testing (str "story-mcp source " rel " — " key " absence")
        (let [stripped (fx/strip-comments-and-strings (fx/read-source rel))]
          (is (not (str/includes? stripped (marker-key->literal key)))
              (str key " literal found in " rel
                   " (in code, after stripping comments/docstrings).\n"
                   "If story-mcp now emits this marker, update "
                   "`canonical-markers` to include :story-mcp in "
                   ":servers, add a story-mcp fixture, and extend "
                   "`server-source-files`.")))))))

;; ---------------------------------------------------------------------------
;; Envelope indicator-field gate (rf2-2499j MUST-level pin).
;;
;; Per Conventions §Cross-MCP indicator-field vocabulary and Spec 009
;; §Size elision in traces — Indicator field on tool responses, tools
;; that return structured response maps MUST carry an `:elided-large`
;; count alongside the existing `:dropped-sensitive` count, one MUST-
;; level row per consumer-facing tool that walks a tree-typed payload.
;;
;; Conformance contract:
;;
;; 1. Schema-level: both envelope slots validate as
;;    `[:map {:closed false} [<slot> nat-int?]]`. Fixtures sourced from
;;    each emitting server.
;; 2. Source-text pin: every server in `:envelope-emitters` carries
;;    BOTH the `:dropped-sensitive` literal AND the `:elided-large`
;;    literal (parity — one without the other is the round-2 audit
;;    must-fix this gate defends against).
;; 3. story-mcp absence tripwire: today story-mcp does not walk any
;;    tree-typed payload through `elide-wire-value` (it operates on
;;    small, structured story/variant metadata that stays under the
;;    wire-cap by construction); neither slot appears in its source.
;;    When story-mcp adopts a walker, the reviewer adds it to
;;    `:envelope-emitters` AND wires the parity emission — both at
;;    once, per the MUST-level pin.
;;
;; The mcp-base vocab ns (`tools/mcp-base/src/re_frame/mcp_base/vocab.cljc`)
;; reserves the two slot KEYS as constants (`dropped-sensitive-key`,
;; `elided-large-key`); the count-walker helper
;; (`count-elided-markers`) lives in the sibling `elision` ns
;; (`tools/mcp-base/src/re_frame/mcp_base/elision.cljc`) — a runtime
;; tree-walker, not a constant, so it doesn't sit in the vocabulary
;; catalogue. Consumers import either ns to keep the key bytes byte-
;; identical across servers.
;; ---------------------------------------------------------------------------

(def envelope-indicator-slots
  "Conformance contract for the two unqualified envelope-indicator
  slots. Each entry pins the schema, per-server fixtures, and the set
  of servers that emit the slot today. The two slots are siblings —
  any server that emits one MUST emit the other (the MUST-level
  parity is the round-2 audit fix this gate enforces)."
  [{:slot     :dropped-sensitive
    :schema   DroppedSensitive
    :emitters #{:pair2-mcp}
    :fixtures {:pair2-mcp-trace-window
               {:ok? true :epochs [] :dropped-sensitive 3}
               :pair2-mcp-snapshot
               {:ok? true :snapshot {} :dropped-sensitive 1}}}

   {:slot     :elided-large
    :schema   ElidedLarge
    :emitters #{:pair2-mcp}
    :fixtures {:pair2-mcp-snapshot
               {:ok? true :snapshot {} :elided-large 2}
               :pair2-mcp-get-path
               {:ok? true :exists? true :path [:user :pdf] :value
                {:rf.size/large-elided
                 {:path [:user :pdf]
                  :bytes 102400
                  :type :string
                  :reason :schema
                  :hint "User PDF; fetch via get-path."
                  :handle [:rf.elision/at [:user :pdf]]}}
                :elided-large 1}}}])

(deftest envelope-indicator-fixtures-conform
  (doseq [{:keys [slot schema fixtures]} envelope-indicator-slots
          [fixture-name fixture-value]   fixtures]
    (testing (str "envelope slot " slot " — fixture " fixture-name)
      (is (m/validate schema fixture-value)
          (str "Fixture " fixture-name " for " slot
               " failed schema validation:\n"
               (me/humanize (m/explain schema fixture-value)))))))

(def ^:private envelope-emitter-source-files
  "Source files that carry the envelope-slot emit sites per server.
  Restricted to the actual tool source — the spec/docs files may
  mention the slots without emitting them.

  Post-rf2-vrbwx split: pair2-mcp's envelope-slot emit point is the
  centralised `wire/with-indicators` helper (rf2-dfk28); the literals
  live in `wire.cljs`. Per-tool routing through the helper is pinned
  in detail by `indicator_field_test.clj`; this gate just asserts the
  two literals appear in the canonical helper location."
  {:pair2-mcp ["tools/pair2-mcp/src/re_frame_pair2_mcp/tools/wire.cljs"]
   :causa-mcp []          ;; impl not landed; spec mentions don't count
   :story-mcp []})        ;; doesn't walk tree-typed payloads today

(deftest envelope-slot-parity-in-pair2-mcp
  ;; MUST-level pin (Conventions rf2-2499j, Spec 009 §Indicator field
  ;; on tool responses): every server that emits one slot MUST emit
  ;; the other. The round-2 alignment audit (rf2-zjqh8) caught
  ;; pair2-mcp emitting only `:dropped-sensitive`; this gate locks
  ;; in the parity so the regression can't return silently.
  (let [files (get envelope-emitter-source-files :pair2-mcp)]
    (is (seq files)
        "No source files registered for pair2-mcp envelope emit sites.")
    (doseq [rel files]
      (let [src (fx/read-source rel)]
        (testing (str "pair2-mcp " rel " — :dropped-sensitive literal")
          (is (str/includes? src ":dropped-sensitive")
              (str ":dropped-sensitive literal missing from " rel)))
        (testing (str "pair2-mcp " rel " — :elided-large literal")
          (is (str/includes? src ":elided-large")
              (str ":elided-large literal missing from " rel
                   " — parity break per Conventions rf2-2499j.")))))))

(deftest story-mcp-still-emits-zero-envelope-indicators
  ;; Tripwire mirroring `story-mcp-still-emits-zero-cross-mcp-markers`.
  ;; The day story-mcp adopts a tree-typed-payload walker (e.g. wires
  ;; `elide-wire-value` over the `:app-db` slot in `preview-variant` /
  ;; `run-variant`), both envelope slots MUST land together. This
  ;; tripwire flips RED on the FIRST adoption so the reviewer can't
  ;; merge half the parity.
  (let [story-files ["tools/story-mcp/src/re_frame/story_mcp/protocol.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/cap.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/registry.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/helpers.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/schemas.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/dev.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/docs.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/testing.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/write.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/recorder.cljc"]
        slots       [":dropped-sensitive" ":elided-large"]]
    (doseq [rel  story-files
            slot slots]
      (testing (str "story-mcp source " rel " — " slot " absence")
        (is (not (str/includes? (fx/read-source rel) slot))
            (str slot " literal found in " rel
                 ".\nIf story-mcp now walks a tree-typed payload, "
                 "the OTHER envelope slot MUST land in the same commit "
                 "(Conventions rf2-2499j MUST-level parity). Update "
                 "`envelope-emitter-source-files` and "
                 "`envelope-indicator-slots`."))))))

;; ---------------------------------------------------------------------------
;; `:rf.mcp/cursor-stale` reason-value gate (rf2-i3ffz F-GAP-5).
;;
;; Unlike the wrapper-shaped markers in `canonical-markers` above,
;; `:rf.mcp/cursor-stale` rides as the `:reason` value on a generic
;; `{:ok? false ...}` error envelope (per `mcp-base/vocab.cljc/
;; cursor-stale-reason`). The conformance contract is the keyword
;; itself: a rename or pluralisation would silently break every agent
;; that pattern-matches on it.
;;
;; The pin shape mirrors the wrapper-marker pins above:
;;   1. fixture validates against `CursorStaleResult` schema.
;;   2. literal appears in pair2-mcp's emit-source (mcp-base/vocab.cljc).
;;   3. literal appears in pair2-mcp's doc-sources (003-Tool-Catalogue.md).
;;   4. no near-miss spelling co-exists in any conformance-tracked file.
;; ---------------------------------------------------------------------------

(def ^:private cursor-stale-fixture
  "Canonical pair2-mcp emission shape from `tools/cursor.cljs/
  cursor-stale-result`. The envelope-specific slots (`:tool`,
  `:requested-id`, `:head-id`, `:hint`) are open per-server; the
  load-bearing contract is `:ok? false` + `:reason :rf.mcp/cursor-stale`."
  {:ok?          false
   :reason       :rf.mcp/cursor-stale
   :tool         "watch-epochs"
   :requested-id "epoch-9001"
   :head-id      "epoch-9101"
   :hint         "Cursor's epoch-id is no longer in the runtime ring. Drop the cursor or widen the window."})

(deftest cursor-stale-fixture-conforms-to-schema
  (is (m/validate CursorStaleResult cursor-stale-fixture)
      (str "Fixture for :rf.mcp/cursor-stale failed schema validation:\n"
           (me/humanize (m/explain CursorStaleResult cursor-stale-fixture)))))

(deftest cursor-stale-rejects-non-error-envelopes
  ;; The reason value MUST ride a `:ok? false` envelope — emitting
  ;; `{:ok? true :reason :rf.mcp/cursor-stale}` would be a contract
  ;; break (success doesn't carry a stale-reason).
  (is (not (m/validate CursorStaleResult
                       {:ok? true :reason :rf.mcp/cursor-stale}))
      "CursorStaleResult MUST reject :ok? true")
  (is (not (m/validate CursorStaleResult
                       {:ok? false :reason :rf.mcp/cursor-stales}))
      "CursorStaleResult MUST reject the pluralised near-miss"))

(deftest cursor-stale-literal-in-pair2-mcp-emit-source
  ;; The canonical declaration lives in mcp-base/vocab.cljc — same
  ;; emit-source as the wrapper markers. Stripped before grep so a
  ;; rename trips the gate even if the old name still appears in a
  ;; docstring.
  (let [literal "\":rf.mcp/cursor-stale\""
        ;; Quoted because pr-str on the keyword renders it without the
        ;; quotes — we want to match the literal token in source code.
        literal (subs literal 1 (dec (count literal)))
        rel     "tools/mcp-base/src/re_frame/mcp_base/vocab.cljc"
        stripped (fx/strip-comments-and-strings (fx/read-source rel))]
    (is (str/includes? stripped literal)
        (str literal " missing from " rel
             " AFTER stripping docstrings/comments. The canonical "
             "declaration moved — update this test or restore the "
             "literal."))))

(deftest cursor-stale-literal-in-pair2-mcp-doc-sources
  ;; Doc-source pin — looser, raw includes? against the prose docs
  ;; that catalogue pagination semantics.
  (let [literal ":rf.mcp/cursor-stale"
        files   (get doc-source-files :pair2-mcp)]
    (is (some (fn [rel] (str/includes? (fx/read-source rel) literal)) files)
        (str literal " missing from pair2-mcp doc-sources " files))))

(deftest cursor-stale-no-near-miss-in-any-server-source
  ;; Defence-in-depth: a rename to a near-miss form (snake_case,
  ;; pluralised, predicate `?` suffix) MUST NOT co-exist anywhere in
  ;; the conformance-tracked source/spec tree. Mirrors the marker-key
  ;; near-miss anti-pin above.
  (doseq [variant (near-miss-variants :rf.mcp/cursor-stale)
          [server files] all-source-files
          rel files]
    (testing (str server " — " rel " — near-miss " variant)
      (is (not (str/includes? (fx/read-source rel) variant))
          (str "Found near-miss variant " variant
               " for :rf.mcp/cursor-stale in " server "/" rel
               " — vocabulary-drift bug.")))))

;; ---------------------------------------------------------------------------
;; `notifications/progress` streaming gate (rf2-i3ffz F-GAP-1).
;;
;; pair2-mcp's `subscribe` streaming tool emits exactly one
;; `notifications/progress` per matching batch (per `NAMING.md`
;; §"subscribe / unsubscribe"). Before this gate landed:
;;
;;   - `end-to-end-pair2.cjs` exercised `subscribe` only in degraded
;;     mode (returns `isError: true` with `:nrepl-port-not-found`); the
;;     streaming wire-shape was never asserted.
;;   - `live-pair2-overflow.cjs` only exercised `eval-cljs`.
;;   - No test observed a real `notifications/progress` frame.
;;
;; The pin shape mirrors the OverflowBody cross-encoding posture above:
;;
;;   1. fixture validates against `Pair2ProgressNotificationParams`.
;;   2. literal `"notifications/progress"` appears in pair2-mcp's emit
;;      site (`tools/subscribe.cljs`).
;;   3. the JS-side hand-rolled assertion in
;;      `live-pair2-subscribe.cjs` carries a substring for every
;;      required field on the Malli schema (the cross-encoding gate;
;;      a tightening on one side without the other trips this test).
;; ---------------------------------------------------------------------------

(def ^:private pair2-progress-fixture
  "Canonical pair2-mcp `notifications/progress` params shape — what
  `subscribe` emits per tick. The `:message` slot is the EDN-printed
  batch (variable per-tick); `:_meta.data` carries the structured
  counts + overflow-reason slot."
  {:progressToken "probe-token-42"
   :progress      1
   :message       "{:sub-id \"sub-abc\" :events [] :dropped-events 0 :dropped-bytes 0}"
   :_meta         {:data {:dropped-events  0
                          :dropped-bytes   0
                          :overflow-reason nil}}})

(deftest pair2-progress-fixture-conforms-to-schema
  (is (m/validate Pair2ProgressNotificationParams pair2-progress-fixture)
      (str "Fixture for notifications/progress failed schema validation:\n"
           (me/humanize
             (m/explain Pair2ProgressNotificationParams pair2-progress-fixture)))))

(deftest pair2-progress-overflow-reason-variant-conforms
  ;; The :overflow-reason slot is `[:maybe :string]` — it carries
  ;; either a pr-str'd keyword (`:max-buffered-events` /
  ;; `:max-buffered-bytes`) or nil. Validate both shapes.
  (is (m/validate
        Pair2ProgressNotificationParams
        (assoc-in pair2-progress-fixture
                  [:_meta :data :overflow-reason]
                  ":max-buffered-events"))
      "overflow-reason as pr-str'd keyword MUST validate")
  (is (m/validate
        Pair2ProgressNotificationParams
        (assoc-in pair2-progress-fixture
                  [:_meta :data :overflow-reason]
                  ":max-buffered-bytes"))
      "overflow-reason as pr-str'd keyword (bytes) MUST validate"))

(deftest pair2-progress-rejects-missing-required-slots
  ;; Tightening: an emit missing `:progressToken`, `:progress`, or
  ;; `:_meta.data` MUST fail. The slot is the load-bearing contract; a
  ;; future regression that drops one would silently break agent-host
  ;; correlation (progressToken) or polling cadence (progress).
  (is (not (m/validate Pair2ProgressNotificationParams
                       (dissoc pair2-progress-fixture :progressToken)))
      "missing :progressToken MUST fail")
  (is (not (m/validate Pair2ProgressNotificationParams
                       (dissoc pair2-progress-fixture :progress)))
      "missing :progress MUST fail")
  (is (not (m/validate Pair2ProgressNotificationParams
                       (dissoc pair2-progress-fixture :_meta)))
      "missing :_meta MUST fail")
  (is (not (m/validate Pair2ProgressNotificationParams
                       (update-in pair2-progress-fixture [:_meta] dissoc :data)))
      "missing :_meta.data MUST fail")
  (is (not (m/validate Pair2ProgressNotificationParams
                       (update-in pair2-progress-fixture [:_meta :data] dissoc :dropped-events)))
      "missing :_meta.data.dropped-events MUST fail"))

(deftest pair2-progress-emit-literal-in-source
  ;; Source-text pin: the literal `"notifications/progress"` MUST
  ;; appear in pair2-mcp's `subscribe.cljs` emit site. The MCP spec
  ;; pins the method name; a regression that emitted
  ;; `"notifications/progressing"` or moved the emit to a non-streaming
  ;; method would surface here.
  ;;
  ;; NOTE: this literal lives INSIDE a string slot (`{:method
  ;; "notifications/progress"}`), so the usual
  ;; `strip-comments-and-strings` discriminator can't be applied — it
  ;; would zero out the string body we need to grep. Raw `str/includes?`
  ;; against the source is the right tool: docstrings on this file do
  ;; not mention the method name, so a false positive from a comment
  ;; cannot happen.
  (let [rel "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/subscribe.cljs"
        src (fx/read-source rel)]
    (is (str/includes? src "\"notifications/progress\"")
        (str "literal \"notifications/progress\" missing from " rel
             ". The emit moved or the method name drifted."))))

(def ^:private live-pair2-subscribe-js-rel
  "Relative path to the hand-rolled JS `notifications/progress`
  assertion. Mirrors `live-pair2-overflow-js-rel` for the OverflowBody
  cross-encoding gate."
  "tools/mcp-conformance/test/live-pair2-subscribe.cjs")

(def ^:private pair2-progress-js-required-grep-markers
  "Substrings the JS `assertProgressParams` MUST contain to pin every
  required field on `Pair2ProgressNotificationParams`. Each entry is
  `[malli-path js-substring]` — the path for error reporting, the
  substring as the grep target. Mirrors the OverflowBody table above:
  a field added to the Malli schema MUST add a row here; a field
  removed MUST remove a row."
  [[":progressToken"
    "'progressToken',  (v) => v !== undefined,"]
   [":progress : int"
    "'progress',       (v) => typeof v === 'number',"]
   [":message : string"
    "'message',        (v) => typeof v === 'string',"]
   [":_meta : map"
    "'_meta',          (v) => v && typeof v === 'object',"]
   [":_meta.data : map"
    "params._meta.data MUST be map"]
   [":_meta.data.dropped-events : nat-int"
    "'dropped-events', (v) => typeof v === 'number' && v >= 0"]
   [":_meta.data.dropped-bytes : nat-int"
    "'dropped-bytes',  (v) => typeof v === 'number' && v >= 0"]
   [":_meta.data.overflow-reason : maybe-string"
    "'overflow-reason'"]])

(deftest js-assertProgressParams-pins-every-pair2-progress-required-field
  ;; Cross-encoding sanity gate (rf2-i3ffz F-GAP-1, mirrors rf2-0zqox
  ;; for OverflowBody). For every required field on the Malli
  ;; `Pair2ProgressNotificationParams` schema, the JS
  ;; `assertProgressParams` function MUST carry a substring that
  ;; asserts the same shape. Missing fields trip this gate with the
  ;; field name in the error.
  (let [js-src (fx/read-source live-pair2-subscribe-js-rel)]
    (doseq [[field grep-pattern] pair2-progress-js-required-grep-markers]
      (testing (str "JS assertProgressParams pins field " field)
        (is (str/includes? js-src grep-pattern)
            (str "Field `" field
                 "` (Malli `Pair2ProgressNotificationParams`) is not "
                 "pinned by the JS `assertProgressParams` in "
                 live-pair2-subscribe-js-rel
                 ". Looked for substring: " (pr-str grep-pattern)
                 ".\nIf you tightened the Malli schema, mirror the "
                 "change in the JS assertion; if you loosened it, "
                 "remove the entry from "
                 "`pair2-progress-js-required-grep-markers`."))))))
