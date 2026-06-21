(ns re-frame.mcp-conformance.wire-vocab-test
  "Cross-MCP wire-vocabulary conformance (rf2-j2z7o).

  Two MCP servers ship under `tools/`: re-frame2-pair-mcp and story-mcp.
  re-frame2-pair-mcp owns the reserved cross-server **wire vocabulary** —
  namespaced map keys an agent recognises identically across every
  server that adopts it. (xray-mcp was dropped in rf2-bu21t — xray
  now ships as a Clojars-only library, not an MCP server.)

  Six top-level wrapper markers (the `canonical-markers` table below is
  the source of truth — see it rather than re-counting this list),
  plus the `:rf.elision/at` fetch-handle tag (embedded inside the
  `:rf.size/large-elided` body's `:handle` slot — not a standalone
  marker; pinned via the elision-marker body schema):

  - `:rf.mcp/overflow`      — token-budget overflow marker
                              (re-frame2-pair-mcp `tools.cljs` `overflow-payload`)
  - `:rf.mcp/summary`       — tree-summary lazy-mode marker
                              (re-frame2-pair-mcp `tools.cljs` `tree-summary`)
  - `:rf.mcp/dedup-table`   — structural-dedup wrapper
                              (re-frame2-pair-mcp `tools.cljs` `dedup-value`)
  - `:rf.mcp/diff-from`     — diff-encoded `:db-after` marker; the
                              body slot is `:sections` — a vector of
                              path-headed cluster sections, each with
                              `:section-path` + `:section-kind` +
                              `:patches` (rf2-qeous, cross-MCP
                              vocabulary per re-frame2-pair-mcp Principles
                              §\"Cross-MCP vocabulary\"; canonical
                              encoder
                              `re-frame.mcp-base.diff-encode/diff-encode-db-after`)
  - `:rf.size/large-elided` — size-elision wire marker
                              (spec/Spec-Schemas §`:rf/elision-marker`,
                               re-frame2-pair-mcp Principles §\"Size-elision\")
  - `:rf.mcp/cache-hit`     — per-session response-cache hit marker
                              (re-frame2-pair-mcp `cache.cljs`
                              `cache-hit-payload`; literal in
                              `mcp-base/vocab.cljc` `cache-hit-key`,
                              rf2-i3ffz F-GAP-4)
  - `:rf.elision/at`        — size-elision fetch-handle tag, embedded
                              inside the `:rf.size/large-elided` body's
                              `:handle` slot per `ElisionMarkerBody`
                              (NOT a standalone top-level marker)

  story-mcp emits two of these markers today — `:rf.mcp/dedup-table`
  (rf2-90eft, the wire-boundary structural-dedup transform) and
  `:rf.mcp/overflow` (rf2-yxgcsz, its wire-boundary token-cap), both via
  the shared `mcp-base` builders so the shapes stay byte-identical with
  re-frame2-pair-mcp. Its remaining vocabulary is namespaced under
  `:rf.story/*`, `:rf.assert/*`, `:rf.error/*` per its own spec. For
  every marker story-mcp does NOT yet emit, the conformance gate guards
  the *contract*: when story-mcp adopts one it MUST use the canonical
  shape, not invent a near-miss — the `story-mcp-still-emits-zero-
  uncontracted-cross-mcp-markers` tripwire below fires the day a NEW
  uncontracted marker leaks in.

  ## What this test guards

  1. **One canonical schema per marker.** A single Malli schema lives
     here. Every fixture EDN representing a server's actual emission
     shape MUST validate against the canonical schema.

  2. **Per-server fixture coverage.** Each marker has at least one
     re-frame2-pair-mcp fixture exercising the schema. When a future MCP
     server adopts the marker, a parallel fixture asserts the shared
     contract.

  3. **Source-text vocabulary pin.** A grep against each server's
     source (re-frame2-pair-mcp `src/`) asserts the canonical literal appears
     AND no near-miss variant (e.g. `:rf.mcp/overflows`,
     `:rf.mcp/dedup_table`, the underscore form) appears. A rename
     in any server surfaces here.

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
  authored fixtures to the actual source/spec text."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [de-dupe.core    :as dd]
            [malli.core      :as m]
            [malli.error     :as me]
            ;; rf2-hvn83u — the canonical framework wire-elision walker +
            ;; the EP-0025 commit-plane `:large` classification effect, so the
            ;; `:rf.size/large-elided` gate drives the REAL emitter LIVE over a
            ;; classified `:large` slot (not a fixture).
            [re-frame.core   :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame  :as frame]
            ;; rf2-9wvwpa — the SECOND framework emitter of the
            ;; `:rf.size/large-elided` marker: the schemas-artefact
            ;; validation-failure size-safety arm (Spec 010 §`:large?`).
            ;; Required so the gate below can drive `validate-event!`
            ;; LIVE over a `:large?`-flagged schema and validate the
            ;; emitted failure-trace marker against `ElisionMarker`.
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.mcp-base.diff-encode :as de]
            [re-frame.mcp-base.overflow :as mcp-overflow]
            [re-frame.mcp-conformance.fixtures :as fx]
            ;; rf2-7ckmwx — the canonical schemas + `canonical-markers`
            ;; catalogue moved to a shared support ns so the focused
            ;; marker-family test namespaces each :require ONE place. See
            ;; `schemas.clj` for the full schema set; the contract-bearing
            ;; data lives there, the assertions live here.
            [re-frame.mcp-conformance.wire-vocab.schemas
             :refer [Overflow Summary DedupTable DiffFromBody
                     ElisionMarker CacheHit DroppedSensitive ElidedLarge
                     canonical-markers]]
            ;; rf2-7ckmwx — shared source-text pin inventories + near-miss
            ;; helpers (`emit-source-files` / `doc-source-files` /
            ;; `all-source-files` / `marker-key->literal` /
            ;; `near-miss-variants`).
            [re-frame.mcp-conformance.wire-vocab.source-pins :as pins]))

;; ---------------------------------------------------------------------------
;; Repo-root + slurp helpers live in `re-frame.mcp-conformance.fixtures`
;; (rf2-113ti). Shared across the conformance test namespaces in
;; this artefact.
;;
;; rf2-7ckmwx — this namespace was split. The canonical schemas +
;; `canonical-markers` catalogue moved to
;; `re-frame.mcp-conformance.wire-vocab.schemas`; the shared source-pin
;; inventories + near-miss helper moved to
;; `re-frame.mcp-conformance.wire-vocab.source-pins`; and five
;; independent marker families moved to their own `*_test.clj`
;; namespaces (cursor-stale, result-envelope, redacted-sentinel,
;; progress-notification, cascade-bundle). This ns keeps the CORE
;; wrapper-marker contract: fixture-conformance over `canonical-markers`,
;; the per-marker negative/live-emission gates, the marker-literal source
;; pins, the JS cross-encoding pin, server-coverage, the story-mcp
;; inventory tripwires, and the envelope indicator-field slots.
;;
;; ## Where to touch when adding a new cross-MCP marker (rf2-7ckmwx)
;;
;;   - Wrapper-shaped marker (`{<key> <body>}`): add the schema to
;;     `schemas.clj`, add an entry to `schemas/canonical-markers` (key +
;;     schema + per-server fixtures + `:servers`), and — if the literal
;;     home / doc-source moved — extend `source_pins.clj`. The generic
;;     fixture-conformance + source-pin sweeps in THIS file then cover it.
;;   - Non-wrapper marker (a `:reason` value, a bare scalar, a
;;     tagged-union, a streaming-notification shape): give it its own
;;     `*_test.clj` namespace (the cursor-stale / result-envelope /
;;     redacted-sentinel / progress-notification / cascade-bundle files
;;     are the templates) requiring the shared `schemas` + `source-pins`
;;     support nses. Keep the schema co-located with its tests when it is
;;     referenced only by that family.
;;   - Cross-encoding (JS) pin: if a live `.cjs` harness re-encodes the
;;     shape, add the per-field grep-marker table next to the family's
;;     fixture (see the overflow / progress JS pins).
;;   - Near-miss anti-pin: every new marker should be swept by
;;     `near-miss-variants` (wrapper markers) or a bespoke variant set
;;     (scalars — see redacted-sentinel).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Fixture conformance — every authored fixture validates against the
;; canonical schema for its marker. The primary conformance assertion:
;; per-server fixture shapes all conform to the same single schema. With
;; re-frame2-pair-mcp as the sole live emitter today, per-marker fixture variants
;; (e.g. `:counts` vs `:count`, integer vs namespaced-keyword keys) pin
;; the schema's alternate shapes so a future second MCP server adopting
;; them validates without surprise.
;; ---------------------------------------------------------------------------

(deftest every-fixture-conforms-to-its-canonical-schema
  (doseq [{:keys [key schema fixtures]} canonical-markers
          [fixture-name fixture-value] fixtures]
    (testing (str "marker " key " — fixture " fixture-name)
      (is (m/validate schema fixture-value)
          (str "Fixture " fixture-name " for " key
               " failed schema validation:\n"
               (me/humanize (m/explain schema fixture-value)))))))

(deftest every-canonical-marker-has-required-fixture-count
  ;; Every marker MUST carry >=1 fixture; multi-server markers MUST
  ;; carry >=2. Single-server today (all markers are re-frame2-pair-mcp-only
  ;; post rf2-bu21t), so the >=1 floor applies; the >=2 path stays
  ;; live for any future MCP server adoption.
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

(deftest every-multi-server-marker-fixtures-cover-each-server
  ;; rf2-87h71e LOW — the per-server fixture-coverage claim in the header
  ;; (guard #2) was NOT enforced. `every-canonical-marker-has-required-
  ;; fixture-count` asserts only `(count fixtures) >= 2` for a multi-server
  ;; marker — never that the fixtures cover DISTINCT servers. So
  ;; `:rf.mcp/overflow` (3 fixtures: 2 re-frame2-pair variants + 1
  ;; story-mcp) would still pass with the `:story-mcp` fixture dropped
  ;; (leaving 2 pair fixtures), silently losing the cross-server emission
  ;; pin. This gate asserts that for EVERY server in `:servers`, at least
  ;; one fixture is tagged for it — keyed by the catalogue's fixture-key
  ;; naming convention: every fixture key starts with its server's name
  ;; (`:re-frame2-pair-mcp...`, `:story-mcp...`). A dropped per-server
  ;; fixture now trips RED directly.
  (let [;; The servers the catalogue knows about, longest-first so a
        ;; prefix match resolves the most specific server (no overlap
        ;; today, but order-stable regardless).
        known-servers (->> canonical-markers
                           (mapcat :servers)
                           distinct
                           (sort-by (comp - count name)))
        ;; Resolve a fixture key to the server whose name prefixes it.
        ;; Returns nil for an un-prefixed key — which the assertion below
        ;; treats as a fixture that covers NO declared server (a naming
        ;; violation surfaces as a missing-server failure).
        fixture-server (fn [fixture-key]
                         (let [fname (name fixture-key)]
                           (first (filter #(or (= fname (name %))
                                               (str/starts-with? fname (str (name %) "-")))
                                          known-servers))))]
    (doseq [{:keys [key fixtures servers]} canonical-markers]
      (testing (str "marker " key " — per-server fixture coverage")
        (let [covered (into #{} (keep fixture-server) (keys fixtures))]
          (doseq [server servers]
            (is (contains? covered server)
                (str key " declares server " server " in :servers but no "
                     "fixture is tagged for it (fixture keys must start "
                     "with the server name; got fixtures "
                     (vec (keys fixtures)) " covering servers " covered "). "
                     "rf2-87h71e: a multi-server marker's per-server "
                     "fixture-coverage claim must be enforced, not just the "
                     ">=2 count."))))))))

(deftest overflow-empty-body-is-rejected
  ;; rf2-kn8cj (refactor-audit r2 of rf2-azk9c §F-VOCAB-2): the previous
  ;; `ReFrame2PairOverflowBody` schema marked every slot except `:limit`
  ;; `{:optional true}`, so an emit shaped as
  ;; `{:rf.mcp/overflow {:limit :reached}}` alone validated. That
  ;; under-constrained the cross-server contract: an emit MUST carry
  ;; re-frame2-pair-mcp's shape (`:cap-tokens` + `:token-count` + `:tool` +
  ;; `:hint`). The schema now requires every field; this gate pins the
  ;; regression directly.
  (testing "empty body (only :limit :reached) fails validation"
    (is (not (m/validate Overflow {:rf.mcp/overflow {:limit :reached}}))
        "Overflow schema must reject an emit with only :limit :reached — the re-frame2-pair shape requires more fields."))
  (testing "missing-required-re-frame2-pair fields fail validation"
    ;; re-frame2-pair shape lacks :token-count
    (is (not (m/validate Overflow
                         {:rf.mcp/overflow
                          {:limit      :reached
                           :tool       "snapshot"
                           :cap-tokens 5000
                           :hint       "..."}}))
        "re-frame2-pair-shape emit missing :token-count must fail")))

;; ---------------------------------------------------------------------------
;; SummaryBody per-type shape contract (rf2-voux7 finding 1).
;;
;; Pre-fix `SummaryBody` marked `:keys` / `:count` / `:counts` / `:value`
;; all `{:optional true}` with no type-specific predicate, so a malformed
;; `{:type :map :bytes 1}` (a map carrying neither `:keys` nor a count)
;; and a scalar with no `:value` both validated — unusable markers a
;; future server could ship while the gate stayed green. The schema is
;; now a `[:multi {:dispatch :type} ...]` enforcing the documented per-
;; type required slots, with each arm CLOSED to its slot set (a cross-
;; type slot leak — a scalar carrying `:keys`, a vector carrying
;; `:value` — is rejected too). These gates pin both the positive
;; documented shapes and the negative malformed ones.
;; ---------------------------------------------------------------------------

(deftest summary-body-enforces-per-type-shape
  (testing "map WITHOUT :keys fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :map :count 3 :bytes 10}}))
        "a map summary MUST carry :keys"))
  (testing "map WITHOUT :count AND WITHOUT :counts fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :map :keys [:a :b] :bytes 10}}))
        "a map summary MUST carry :count or :counts (not neither)"))
  (testing "map with ONLY :type + :bytes (the bead's malformed example) fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :map :bytes 1}}))
        "the documented unusable map marker {:type :map :bytes 1} MUST fail"))
  (testing "vector WITHOUT :count fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :vector :bytes 10}}))
        "a vector summary MUST carry :count"))
  (testing "set WITHOUT :count fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :set :bytes 10}}))
        "a set summary MUST carry :count"))
  (testing "seq WITHOUT :count fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :seq :bytes 10}}))
        "a seq summary MUST carry :count"))
  (testing "scalar WITHOUT :value fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :scalar :bytes 2}}))
        "a scalar summary MUST carry :value"))
  (testing "cross-type slot leak: scalar carrying :keys fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :scalar :value 1 :bytes 2 :keys [:a]}}))
        "a scalar summary MUST NOT carry the maps-only :keys slot"))
  (testing "cross-type slot leak: vector carrying :value fails"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :vector :count 3 :bytes 10 :value 1}}))
        "a vector summary MUST NOT carry the scalar-only :value slot"))
  (testing "unknown :type fails (no dispatch arm)"
    (is (not (m/validate Summary {:rf.mcp/summary {:type :bogus :bytes 1}}))
        "an unrecognised :type has no dispatch arm and MUST fail"))
  (testing "positive guard: every documented shape still validates"
    (is (m/validate Summary {:rf.mcp/summary {:type :map :keys [:a] :count 1 :bytes 10}})
        "map with :keys + :count + :bytes validates")
    (is (m/validate Summary {:rf.mcp/summary {:type :map :keys [:a] :counts {:a 1} :bytes 10}})
        "map with :keys + :counts + :bytes validates (per-key variant)")
    (is (m/validate Summary {:rf.mcp/summary {:type :map :keys [:a] :count 1 :bytes 10 :keys-truncated? true}})
        "map with the optional :keys-truncated? slot validates")
    (is (m/validate Summary {:rf.mcp/summary {:type :vector :count 3 :bytes 10}})
        "vector with :count + :bytes validates")
    (is (m/validate Summary {:rf.mcp/summary {:type :set :count 3 :bytes 10}}))
    (is (m/validate Summary {:rf.mcp/summary {:type :seq :count 3 :bytes 10}}))
    (is (m/validate Summary {:rf.mcp/summary {:type :scalar :value 42 :bytes 2}})
        "scalar with :value + :bytes validates")))

;; ---------------------------------------------------------------------------
;; Single-key wrapper contract (rf2-voux7 finding 2).
;;
;; Every wire marker is a single reserved wrapper key (the file header:
;; "The marker is always a single-key map keyed by the reserved keyword
;; ... Agents pattern-match on the top-level reserved key"). Pre-fix the
;; wrapper schemas (`Overflow` / `Summary` / `DedupTable` /
;; `ElisionMarker` / `CacheHit`) were OPEN `[:map ...]`, so a payload
;; carrying the marker PLUS an unrelated sibling top-level key validated
;; — a mixed-envelope emission a uniform cross-server client can't
;; pattern-match. The wrappers are now `{:closed true}`; these gates pin
;; that an extra sibling key is rejected for each. (`:rf.mcp/diff-from`
;; is the documented exception — marker key + `:sections` ride as a
;; closed PAIR; covered by `diff-from-rejects-extra-sibling-key` below.)
;; ---------------------------------------------------------------------------

(deftest wrapper-markers-reject-extra-sibling-key
  (testing "Overflow rejects an extra sibling top-level key"
    (is (not (m/validate Overflow
                         {:rf.mcp/overflow {:limit :reached :token-count 1 :cap-tokens 1
                                            :tool "snapshot" :hint "..."}
                          :sneaky :key}))
        "a marker wrapper MUST be a single key — an extra sibling fails"))
  (testing "Summary rejects an extra sibling top-level key"
    (is (not (m/validate Summary
                         {:rf.mcp/summary {:type :vector :count 3 :bytes 10}
                          :sneaky :key}))))
  (testing "DedupTable rejects an extra sibling top-level key"
    (is (not (m/validate DedupTable
                         {:rf.mcp/dedup-table {:de-dupe.cache/cache-0 {:a 1}}
                          :sneaky :key}))))
  (testing "ElisionMarker rejects an extra sibling top-level key"
    (is (not (m/validate ElisionMarker
                         {:rf.size/large-elided {:path [:a] :bytes 1 :type :map
                                                 :reason :effect :hint nil
                                                 :handle [:rf.elision/at [:a]]}
                          :sneaky :key}))))
  (testing "CacheHit rejects an extra sibling top-level key"
    (is (not (m/validate CacheHit
                         {:rf.mcp/cache-hit {:hash 1 :unchanged-since 1 :tool "snapshot"
                                             :via :precheck :hint "..."}
                          :sneaky :key}))))
  (testing "positive guard: each canonical single-key wrapper still validates"
    (is (m/validate Overflow {:rf.mcp/overflow {:limit :reached :token-count 1 :cap-tokens 1
                                                :tool "snapshot" :hint "..."}}))
    (is (m/validate Summary {:rf.mcp/summary {:type :vector :count 3 :bytes 10}}))
    (is (m/validate DedupTable {:rf.mcp/dedup-table {:de-dupe.cache/cache-0 {:a 1}}}))
    (is (m/validate CacheHit {:rf.mcp/cache-hit {:hash 1 :unchanged-since 1 :tool "snapshot"
                                                 :via :precheck :hint "..."}}))))

(deftest diff-from-rejects-extra-sibling-key
  ;; `:rf.mcp/diff-from` is NOT a single-key wrapper — it carries the
  ;; marker key + its `:sections` body slot as a documented PAIR. The
  ;; closed map rejects any OTHER sibling top-level key (rf2-voux7
  ;; finding 2): the encoder emits exactly these two keys.
  (testing "rejects a third sibling top-level key beyond the documented pair"
    (is (not (m/validate DiffFromBody
                         {:rf.mcp/diff-from :db-before
                          :sections []
                          :sneaky :key}))
        "a diff-from :db-after carrying a key beyond {marker, :sections} MUST fail"))
  (testing "rejects a missing :sections slot"
    (is (not (m/validate DiffFromBody {:rf.mcp/diff-from :db-before}))
        "the :sections body slot is required"))
  (testing "positive guard: the documented two-key shape validates"
    (is (m/validate DiffFromBody {:rf.mcp/diff-from :db-before :sections []}))))

;; ---------------------------------------------------------------------------
;; DedupTable root-cache contract (rf2-x0pr0 finding 2).
;;
;; The pre-fix schema was `[:map [:rf.mcp/dedup-table :map]]` — it
;; accepted ANY map, including `{}` and caches with no `cache-0` root.
;; But `de-dupe.core/expand` (what an agent host calls) ALWAYS starts at
;; the `de-dupe.cache/cache-0` root, and the Node-side decoder
;; (`tools/mcp-conformance/lib/dedup-envelope.cjs`) THROWS on a missing
;; root. So the canonical JVM contract was strictly LOOSER than the
;; client-visible / Node decoder contract: a root-less table that no
;; real client can expand validated JVM-side. These gates pin the
;; tightened schema's teeth — it now rejects exactly the shapes the Node
;; decoder rejects.
;; ---------------------------------------------------------------------------

(deftest dedup-table-rejects-rootless-cache
  (testing "empty cache `{}` (no root entry) fails validation"
    (is (not (m/validate DedupTable {:rf.mcp/dedup-table {}}))
        "DedupTable must reject an empty cache — it has no de-dupe.cache/cache-0 root, so the agent host's `expand` (and the Node decoder) cannot reconstruct it."))
  (testing "cache with subtrees but NO cache-0 root fails validation"
    ;; cache-1 / cache-2 present but the load-bearing cache-0 root is
    ;; absent — `expand` begins at cache-0 and would throw. The Node
    ;; decoder throws the same way.
    (is (not (m/validate DedupTable
                         {:rf.mcp/dedup-table
                          {:de-dupe.cache/cache-1 {:event-id :foo}
                           :de-dupe.cache/cache-2 {:event-id :bar}}}))
        "DedupTable must reject a cache missing the de-dupe.cache/cache-0 root."))
  (testing "integer-keyed table (de-dupe NEVER emits this) fails validation"
    ;; The removed fixture's shape — fiction the old schema accepted.
    ;; day8/de-dupe keys by namespaced symbols only; an integer-keyed
    ;; table has no cache-0 root and the Node decoder throws on it.
    (is (not (m/validate DedupTable
                         {:rf.mcp/dedup-table
                          {1 {:event-id :foo :handler-id :bar}
                           2 {:event-id :baz}}}))
        "DedupTable must reject an integer-keyed table — de-dupe never emits one and it carries no cache-0 root."))
  (testing "a valid namespaced cache WITH a cache-0 root still validates"
    ;; Guard against over-tightening — the canonical shape MUST pass.
    (is (m/validate DedupTable
                    {:rf.mcp/dedup-table
                     {:de-dupe.cache/cache-0 [:de-dupe.cache/cache-1 :de-dupe.cache/cache-1]
                      :de-dupe.cache/cache-1 {:event-id :foo}}})
        "DedupTable must accept the canonical namespaced cache with a cache-0 root."))
  (testing "the predicate also accepts the symbol root form de-dupe-eq actually emits"
    ;; `de-dupe.core/de-dupe-eq` keys by namespaced SYMBOLS, not
    ;; keywords — assert the schema accepts that representation too.
    (is (m/validate DedupTable
                    {:rf.mcp/dedup-table
                     {'de-dupe.cache/cache-0 {:a 1}}})
        "DedupTable must accept the symbol-keyed root form the encoder emits.")))

;; ---------------------------------------------------------------------------
;; LIVE dedup-table emission + JVM↔Node root agreement (rf2-x0pr0).
;;
;; Drive the REAL `de-dupe.core/de-dupe-eq` encoder (the same fn both
;; pair-mcp and story-mcp call at their wire boundary) over a
;; duplicate-rich value and assert:
;;
;;   1. the wrapped marker validates against the tightened DedupTable
;;      schema (the encoder genuinely emits a cache-0 root — the
;;      regression the fixture-only layer would miss); and
;;   2. after a JSON round-trip (the symbol→string coercion every JSON
;;      transport performs), the table's root key is the byte-for-byte
;;      string the Node decoder hardcodes as `ROOT_CACHE_ID`. This pins
;;      the JVM-encoder ⇄ Node-decoder agreement directly — the cross-
;;      MCP root convention can no longer drift on one side silently
;;      (acceptance criterion 4).
;; ---------------------------------------------------------------------------

(def ^:private node-decoder-root-cache-id
  "The literal `ROOT_CACHE_ID` the Node decoder
  (`tools/mcp-conformance/lib/dedup-envelope.cjs`) requires as the cache
  root. Mirrored here so the live test below pins the JVM-encoder's
  emitted root against the Node-decoder's expectation. A drift on EITHER
  side trips this gate."
  "de-dupe.cache/cache-0")

(deftest dedup-table-emitted-live-by-canonical-encoder
  ;; The load-bearing live-emission gate for `:rf.mcp/dedup-table`. A
  ;; payload with repeated subtrees so de-dupe actually pools them.
  (let [payload   [{:event-id :user/sign-in  :handler-id :auth}
                   {:event-id :user/sign-in  :handler-id :auth}
                   {:event-id :user/sign-out :handler-id :auth}]
        cache     (dd/de-dupe-eq payload)
        wrapped   {:rf.mcp/dedup-table cache}]
    (testing "the encoder emits a cache carrying the canonical cache-0 root"
      (is (contains? cache (dd/make-cache-element 0))
          (str "de-dupe-eq MUST emit a de-dupe.cache/cache-0 root entry. "
               "If this fails the library's root convention changed and "
               "the Node decoder's ROOT_CACHE_ID is now wrong. Got keys: "
               (pr-str (keys cache)))))
    (testing "the live-emitted marker validates against the tightened DedupTable schema"
      (is (m/validate DedupTable wrapped)
          (str "Live-emitted dedup-table failed DedupTable validation:\n"
               (me/humanize (m/explain DedupTable wrapped)))))
    (testing "the round-tripped value expands back to the original payload"
      (is (= payload (dd/expand cache))
          "live de-dupe-eq → expand round-trips the payload"))
    (testing "JVM↔Node root agreement: the JSON string form of the root key matches the Node decoder's ROOT_CACHE_ID"
      ;; The cache keys are namespaced SYMBOLS (`de-dupe.cache/cache-N`).
      ;; The real story-mcp wire serialises them with Cheshire, whose
      ;; `generate-string` renders a namespaced symbol key as its FULL
      ;; `(str sym)` form — namespace preserved — i.e.
      ;; `"de-dupe.cache/cache-0"`. (`clojure.data.json` is the wrong
      ;; model here: it DROPS the namespace to `"cache-0"`, which would
      ;; never round-trip through the Node decoder — so we pin the
      ;; library-independent `(str key)` form Cheshire and the MCP wire
      ;; actually emit.) Assert the root key's string form is exactly
      ;; the literal the Node decoder hardcodes; a drift in the encoder's
      ;; root convention turns THIS gate red before it breaks real
      ;; clients.
      (let [root-key-strs (set (map str (keys cache)))]
        (is (contains? root-key-strs node-decoder-root-cache-id)
            (str "The cache's root key, in its on-the-wire string form, "
                 "MUST be " (pr-str node-decoder-root-cache-id)
                 " — the literal the Node decoder (lib/dedup-envelope.cjs "
                 "ROOT_CACHE_ID) requires. Got key string forms: "
                 (pr-str root-key-strs)))))))

;; ---------------------------------------------------------------------------
;; Node decoder ROOT_CACHE_ID literal pin (rf2-x0pr0).
;;
;; The live test above pins that the JVM encoder's root agrees with the
;; Node decoder's hardcoded `ROOT_CACHE_ID`. This complementary gate
;; pins the OTHER direction: the Node decoder source MUST still declare
;; `ROOT_CACHE_ID = 'de-dupe.cache/cache-0'`. If someone edits the Node
;; decoder's root constant (or the live JVM root convention shifts),
;; one of the two gates trips — they cannot drift independently.
;; ---------------------------------------------------------------------------

(deftest node-decoder-root-cache-id-literal-pinned
  (let [src (fx/read-source "tools/mcp-conformance/lib/dedup-envelope.cjs")]
    (is (str/includes? src
                       (str "ROOT_CACHE_ID = '" node-decoder-root-cache-id "'"))
        (str "The Node decoder MUST declare ROOT_CACHE_ID = '"
             node-decoder-root-cache-id
             "'. If it changed, the JVM DedupTable schema's required-root "
             "and the live JVM↔Node agreement test are now out of sync — "
             "update root-cache-id-name + this pin together."))))

;; ---------------------------------------------------------------------------
;; LIVE marker emission (rf2-80y2h).
;;
;; ## The gap this closes
;;
;; Pre-rf2-80y2h, only `:rf.mcp/overflow` had a LIVE emission assertion
;; (`test/live-re-frame2-pair-overflow.cjs`, gated on a real nREPL +
;; Playwright, hermetic on CI). Every other marker was pinned by exactly
;; two layers:
;;
;;   1. an authored fixture validated against the canonical schema
;;      (`every-fixture-conforms-to-its-canonical-schema`); and
;;   2. a source-text grep that the marker LITERAL appears in
;;      `mcp-base/vocab.cljc` (`marker-literal-appears-...-emit-source`).
;;
;; Neither layer observes the actual ENCODER producing the marker. The
;; grep checks only that the literal is DECLARED in vocab.cljc — not that
;; `diff-encode-db-after` still WRITES it into a real epoch. A regression
;; that made `diff-encode-db-after` pass `:db-after` through unchanged
;; (or emit a different key) would: leave the vocab literal in place
;; (grep passes), leave the authored fixture untouched (fixture passes),
;; and ship a tool that silently stopped diff-encoding. Every gate green.
;;
;; ## What this adds
;;
;; A live-emission gate for `:rf.mcp/diff-from`: drive the CANONICAL
;; encoder (`re-frame.mcp-base.diff-encode/diff-encode-db-after`, the
;; same fn re-frame2-pair-mcp's wire pipeline calls) over a real epoch
;; and assert the emitted `:db-after` (a) carries the `:rf.mcp/diff-from`
;; marker key and (b) validates against the canonical `DiffFromBody`
;; schema pinned above. If the encoder stops emitting the marker — the
;; exact regression the fixture+grep layers miss — this gate fails.
;;
;; ## Per-marker live-vs-fixture policy (Axis 4 of the rf2-80y2h audit)
;;
;; Only markers whose EMITTER is JVM-reachable from this pure-JVM gate
;; get a live-emission assertion:
;;
;;   - :rf.mcp/diff-from   — LIVE here. Emitter is mcp-base
;;                           `diff-encode-db-after` (`.cljc`, on the JVM
;;                           classpath via the `:test` alias).
;;   - :rf.mcp/overflow    — LIVE in `live-re-frame2-pair-overflow.cjs`
;;                           (hermetic CI). Emitter is the re-frame2-pair-mcp
;;                           CLJS wire boundary; the marker SHAPE builder
;;                           (`mcp-base/overflow.cljc/overflow-payload`)
;;                           is additionally exercised live below since
;;                           it too is JVM-reachable.
;;   - :rf.size/large-elided — LIVE here (rf2-hvn83u). Emitter is the
;;                           framework wire-elision walker
;;                           (`re-frame.elision/elide-wire-value`, `.cljc`,
;;                           on the JVM classpath via the `:test` alias's
;;                           core `:local/root`). Driven below over a
;;                           classified `:large` slot; the emitted
;;                           marker is validated against the canonical
;;                           `ElisionMarker` schema. This gate is exactly
;;                           what was MISSING when the pre-EP-0015
;;                           `:reason :schema` pin sat stale — the fixture
;;                           +grep layers never observed the real emitter,
;;                           so an `:effect`-emitting runtime validating
;;                           against a `:schema`-only schema went unseen.
;;   - :rf.mcp/summary     — FIXTURE+grep only. Emitter is re-frame2-pair-mcp
;;     :rf.mcp/dedup-table   CLJS (`tools/*.cljs`) with no JVM-reachable
;;     :rf.mcp/cache-hit     counterpart; a pure-JVM live probe is
;;                           impossible. Their bodies are pure data
;;                           transforms already unit-tested in mcp-base /
;;                           re-frame2-pair-mcp; a live SDK probe for each
;;                           is tracked as future work (it requires the
;;                           heavyweight nREPL+Playwright orchestrator the
;;                           overflow path uses).
;; ---------------------------------------------------------------------------

(deftest diff-from-marker-emitted-live-by-canonical-encoder
  ;; The load-bearing live-emission gate. Drive the real encoder and
  ;; assert the marker is actually written — not just declared in vocab.
  (let [epoch    {:db-before {:cart {:items [{:sku "abc"}]}
                              :checkout {:state :idle}
                              :tmp 42}
                  :db-after  {:cart {:items [{:sku "abc"} {:sku "xyz"}]}
                              :checkout {:state :review}}
                  :event     [:cart/add-item {:sku "xyz"}]}
        encoded  (de/diff-encode-db-after epoch)
        db-after (:db-after encoded)]
    (testing "the encoder writes the :rf.mcp/diff-from marker"
      (is (= :db-before (get db-after :rf.mcp/diff-from))
          (str "diff-encode-db-after MUST emit the :rf.mcp/diff-from "
               "marker. If this fails, the encoder stopped diff-encoding "
               ":db-after — the regression the fixture+grep gates miss. "
               "Got :db-after = " (pr-str db-after))))
    (testing "the emitted body validates against the canonical DiffFromBody schema"
      (is (m/validate DiffFromBody db-after)
          (str "Live-emitted :db-after failed DiffFromBody validation:\n"
               (me/humanize (m/explain DiffFromBody db-after)))))
    (testing "the emitted marker decodes back to the original :db-after"
      (is (= epoch (de/decode-db-after encoded))
          "live encode → decode round-trips the epoch"))))

(deftest diff-from-marker-absent-when-encoder-disabled
  ;; The contrapositive: `:full` mode passes :db-after through unchanged,
  ;; so the marker MUST be absent. This pins that the marker's presence
  ;; is genuinely tied to the encoder running — proving the live test
  ;; above isn't accidentally green because something else writes the key.
  (let [epoch  {:db-before {:a 1} :db-after {:a 2}}
        passed (first (de/diff-encode-epochs [epoch] :full))]
    (is (not (contains? (:db-after passed) :rf.mcp/diff-from))
        ":full mode must NOT carry the diff-from marker")
    (is (= {:a 2} (:db-after passed))
        ":full mode passes :db-after through verbatim")))

(deftest overflow-marker-shape-emitted-live-by-canonical-builder
  ;; `:rf.mcp/overflow`'s wire emission is live-tested in
  ;; `live-re-frame2-pair-overflow.cjs`, but its SHAPE builder
  ;; (`mcp-base/overflow.cljc/overflow-payload`) is also JVM-reachable.
  ;; Drive it directly and assert the built marker validates against the
  ;; canonical Overflow schema — a live counterpart to the authored
  ;; overflow fixtures, catching a builder change that drifts the body
  ;; shape without touching the fixtures.
  (let [marker (mcp-overflow/overflow-payload
                 {:tool "snapshot" :token-count 6250 :cap 5000
                  :hint "Narrow the scope."})]
    (is (= :rf.mcp/overflow (first (keys marker)))
        "builder emits the canonical top-level :rf.mcp/overflow key")
    (is (m/validate Overflow marker)
        (str "Live-built overflow marker failed Overflow validation:\n"
             (me/humanize (m/explain Overflow marker))))))

;; ---------------------------------------------------------------------------
;; :rf.size/large-elided — live-emission gate (rf2-hvn83u).
;;
;; The load-bearing gate that catches the exact drift this bead fixed: the
;; canonical schema had `:reason [:enum :schema]` while the runtime emits the
;; declaration SOURCE (EP-0025: `:effect` for the commit-plane classification
;; effect, the canonical default — the large declaration is no longer
;; schema-owned NOR a frame annotation). With only a fixture (authored to match
;; the stale schema) and a source-text grep (literal key only), the schema
;; validated an IMPOSSIBLE shape and a real `:effect` marker would have FAILED
;; — yet every gate stayed green. This gate drives the REAL walker
;; (`re-frame.elision/elide-wire-value`) over a classified `:large` slot and
;; validates the emitted marker against the canonical `ElisionMarker` schema,
;; so a `:reason`-enum (or any body-shape) drift between runtime and schema now
;; turns this gate red.
;;
;; Self-contained runtime setup: this artefact's other tests are pure
;; data and carry no `use-fixtures`, so the gate stands up + tears down
;; its own minimal frame runtime (mirroring
;; `implementation/core/test/re_frame/elision_test.clj`'s `reset-runtime`
;; + `install-class!`).

(defn- elision-live-marker
  "Drive `re-frame.elision/elide-wire-value` LIVE over a classified
  `:large` `app-db` slot and return the emitted `:rf.size/large-elided`
  marker map. `large` is a vector of `:rf/path` vectors classified through
  the EP-0025 commit-plane `:large` classification effect (`:source :effect`).
  `v` is the wire value walked under the `:rf/default` frame scope."
  [large v]
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (elision/clear-warning-cache!)
  (elision/configure! {:rf.size/threshold-bytes 16384})
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    ;; EP-0025: classify the `:large` paths via the commit-plane effect path —
    ;; the same registry write a `reg-event` returning `:large` performs (the
    ;; durable `:large {:app-db …}` frame annotation is removed).
    (frame/swap-runtime-db! :rf/default
      (fn [rt] (elision/apply-classification-effects rt {:large (mapv vec large)})))
    (elision/elide-wire-value v)))

(deftest elision-marker-emitted-live-by-canonical-walker
  ;; Drive the REAL walker over a frame-declared `:large` slot and assert
  ;; the emitted marker (a) is the canonical single-key wrapper and (b)
  ;; validates against the `ElisionMarker` schema — the gate that would
  ;; have caught the `:reason :schema` → `:frame` drift.
  (let [out    (elision-live-marker
                 [[:user :uploaded-pdf]]
                 {:user {:name "Ada" :uploaded-pdf "<<5MB-blob>>"}})
        marker (get-in out [:user :uploaded-pdf])]
    (testing "the walker substitutes a :rf.size/large-elided marker at the declared slot"
      (is (elision/marker? marker)
          (str "elide-wire-value MUST substitute a :rf.size/large-elided "
               "marker at a frame-declared :large slot. Got: " (pr-str marker))))
    (testing "the live-emitted marker validates against the canonical ElisionMarker schema"
      (is (m/validate ElisionMarker marker)
          (str "Live-emitted elision marker failed ElisionMarker validation "
               "— the canonical schema has drifted from the runtime emitter "
               "(this is exactly the rf2-hvn83u :reason :schema vs :frame "
               "drift the fixture+grep layers missed):\n"
               (me/humanize (m/explain ElisionMarker marker)))))
    (testing "the live :reason is the commit-plane classification provenance (EP-0025), NOT the retired :schema"
      (is (= :effect (get-in marker [:rf.size/large-elided :reason]))
          (str "A `:large`-classified slot MUST emit :reason :effect "
               "(EP-0025 — the commit-plane classification effect source). Got: "
               (pr-str (get-in marker [:rf.size/large-elided :reason])))))
    (testing "the marker carries the absolute declared path"
      (is (= [:user :uploaded-pdf]
             (get-in marker [:rf.size/large-elided :path]))))))

(deftest elision-marker-schema-rejects-retired-schema-reason
  ;; Contrapositive: the corrected `ElisionMarker` schema MUST REJECT the
  ;; pre-EP-0015 `:reason :schema` shape. This pins that the fix is not
  ;; merely additive — the impossible runtime shape the old gate uniquely
  ;; validated is now explicitly out of contract, so a regression that
  ;; widened `:reason` back to `:schema` turns this gate red.
  (is (not (m/validate ElisionMarker
                       {:rf.size/large-elided
                        {:path   [:user :uploaded-pdf]
                         :bytes  102400
                         :type   :string
                         :reason :schema
                         :hint   nil
                         :handle [:rf.elision/at [:user :uploaded-pdf]]}}))
      ":reason :schema is the retired pre-EP-0015 shape and MUST NOT validate"))

;; ---------------------------------------------------------------------------
;; :rf.size/large-elided — SECOND live emitter (rf2-9wvwpa).
;;
;; hvn83u's gate above drives the wire-elision walker
;; (`re-frame.elision/elide-wire-value`). But the framework has a SECOND
;; runtime emitter of the same marker: the schemas-artefact validation-
;; failure size-safety arm. When a `:large?`-flagged schema slot fails
;; validation, `re-frame.schemas.validate/validate-event!` substitutes the
;; `:rf.size/large-elided` marker into the value-bearing trace slots
;; (Spec 010 §`:large?`) so the raw blob never rides the
;; `:rf.error/schema-validation-failure` trace.
;;
;; That emitter sat FIXTURE-FREE — exactly the blind spot that let
;; `validate.cljc` ship a non-conformant marker (`:reason :schema`, outside
;; the post-EP-0015 [:frame :marks] enum, and the REQUIRED `:hint` slot
;; omitted) while its docstring falsely claimed `:rf/elision-marker`
;; conformance. The fix delegates to the canonical `elision/->marker`; this
;; gate drives the REAL `validate-event!` emitter LIVE and validates the
;; emitted marker against the SAME canonical `ElisionMarker` schema, so a
;; future drift on the schemas-artefact path turns red here — symmetric to
;; hvn83u's `elide-wire-value` gate.

(defn- schema-validation-failure-marker
  "Drive `re-frame.schemas.validate/validate-event!` LIVE over a
  `:large?`-flagged schema slot whose value fails validation, capture the
  `:rf.error/schema-validation-failure` trace, and return the
  `:rf.size/large-elided` marker substituted into its `:value` slot."
  [blob]
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (let [traces (atom [])]
    (rf/register-listener! :trace ::sv (fn [ev] (swap! traces conj ev)))
    (schemas/validate-event! :upload/save [:upload/save {:blob blob}]
                             {:schema [:cat [:= :upload/save]
                                       [:map [:blob {:large? true} :int]]]})
    (rf/unregister-listener! :trace ::sv)
    (-> (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                       @traces))
        :tags :value)))

(deftest schema-validation-failure-marker-emitted-live-validates
  ;; Drive the REAL schemas-artefact validation-failure emitter and assert
  ;; the substituted marker (a) is the canonical single-key wrapper and (b)
  ;; validates against `ElisionMarker` — the gate that would have caught the
  ;; `:reason :schema` / missing-`:hint` non-conformance this bead fixed.
  (let [blob   (apply str (repeat 200 "X"))
        marker (schema-validation-failure-marker blob)]
    (testing "validate-event! substitutes a :rf.size/large-elided marker on a :large? failure"
      (is (elision/marker? marker)
          (str "validate-event! MUST substitute a :rf.size/large-elided "
               "marker for a :large?-flagged slot's failure. Got: "
               (pr-str marker))))
    (testing "the live-emitted validation-failure marker validates against ElisionMarker"
      (is (m/validate ElisionMarker marker)
          (str "Live-emitted schema-validation-failure marker failed "
               "ElisionMarker validation — the schemas-artefact emitter has "
               "drifted from the canonical contract (rf2-9wvwpa):\n"
               (me/humanize (m/explain ElisionMarker marker)))))
    (testing "the live :reason is the EP-0025 commit-plane classification default, NOT the retired :schema"
      (is (= :effect (get-in marker [:rf.size/large-elided :reason]))
          (str "The validation-failure marker MUST emit :reason :effect "
               "(EP-0025 — the canonical commit-plane classification source). Got: "
               (pr-str (get-in marker [:rf.size/large-elided :reason])))))
    (testing "the marker carries the REQUIRED :hint slot"
      (is (contains? (:rf.size/large-elided marker) :hint)
          ":hint is REQUIRED by ElisionMarkerBody (was previously omitted)"))
    (testing "the large blob survives nowhere verbatim in the marker"
      (is (not (str/includes? (pr-str marker) blob))
          "the whole-value substitution must not re-leak the blob"))))

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
;; literals did not appear in any re-frame2-pair-mcp source code AT ALL (they
;; were imported via `re-frame.mcp-base.vocab/<key>`). A rename inside
;; `mcp-base/vocab.cljc` (the canonical home of every literal) didn't
;; trip the gate. The new emit-side pin closes that hole.
;; ---------------------------------------------------------------------------

;; rf2-7ckmwx — `emit-source-files` / `doc-source-files` /
;; `all-source-files` / `marker-key->literal` / `near-miss-variants`
;; moved to `re-frame.mcp-conformance.wire-vocab.source-pins` (aliased
;; `pins`) so the focused marker-family test namespaces share one home.
;; The sweeps below reference them via `pins/`.

(deftest marker-literal-appears-in-every-contracted-server-emit-source
  ;; The load-bearing pin: every marker literal each server is
  ;; contracted to emit MUST appear as DATA (not docstring/comment) in
  ;; at least one of the registered emit-source files. The
  ;; `strip-comments-and-strings` walker is applied before the grep so
  ;; a rename inside the canonical declaration site (re-frame2-pair-mcp's
  ;; `mcp-base/vocab.cljc`) trips the gate even if old docstrings still
  ;; mention the prior name.
  ;;
  ;; story-mcp is contracted for `:rf.mcp/dedup-table` (rf2-90eft) and
  ;; `:rf.mcp/overflow` (rf2-yxgcsz); both source their literal from
  ;; `mcp-base/vocab.cljc` (the shared emit-source for both servers per
  ;; `source-pins/emit-source-files`), so this loop checks the literal
  ;; is present there for story-mcp too. The doc-source fallback path
  ;; (spec-text-only coverage for an MCP server whose impl hasn't landed)
  ;; is retained for any future server adopted into the cross-MCP family
  ;; before its `src/` ships.
  (doseq [{:keys [key servers]} canonical-markers
          server                servers]
    (testing (str "marker " key " literal in " server " emit-sources")
      (let [literal    (pins/marker-key->literal key)
            emit-files (get pins/emit-source-files server)
            doc-files  (get pins/doc-source-files server)]
        ;; A server with zero emit-sources AND zero doc-sources is a
        ;; gap — a missing catalogue entry the reviewer must add.
        (is (or (seq emit-files) (seq doc-files))
            (str "No emit-sources or doc-sources registered for "
                 server " — extend `emit-source-files` or "
                 "`doc-source-files`."))
        (cond
          ;; impl-landed path: emit-sources MUST carry the literal as
          ;; data after comment/string stripping.
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

          ;; impl-not-landed path: spec-text coverage only. Doc-sources
          ;; are the looser pin — raw `str/includes?`.
          :else
          (is (some (fn [rel]
                      (str/includes? (fx/read-source rel) literal))
                    doc-files)
              (str "Literal " literal " missing from " server
                   " DOC-sources " doc-files
                   ". (No emit-sources registered; spec-text coverage "
                   "is the impl-not-landed stand-in.)")))))))

(deftest marker-literal-appears-in-re-frame2-pair-mcp-doc-sources
  ;; Defence-in-depth: re-frame2-pair-mcp's spec/descriptor docs SHOULD also
  ;; carry each emitted-marker literal for human readers. Looser pin —
  ;; raw `str/includes?` allows docstring mentions; the load-bearing
  ;; check is the emit-source pin above. Drift here means the docs
  ;; lag, not that the emit shape broke.
  (doseq [{:keys [key servers]} canonical-markers
          :when                 (contains? servers :re-frame2-pair-mcp)]
    (testing (str "marker " key " literal in re-frame2-pair-mcp doc-sources")
      (let [literal   (pins/marker-key->literal key)
            doc-files (get pins/doc-source-files :re-frame2-pair-mcp)]
        (is (some (fn [rel]
                    (str/includes? (fx/read-source rel) literal))
                  doc-files)
            (str "Literal " literal
                 " missing from re-frame2-pair-mcp doc-sources " doc-files
                 ". The docs may have re-organised the prose; either "
                 "restore the mention or update `doc-source-files`."))))))

(deftest no-near-miss-variants-appear-in-any-server-source
  ;; Defence-in-depth: a rename to a near-miss form (e.g. snake_case)
  ;; would slip past the literal-presence test if the canonical form
  ;; ALSO still appears somewhere. This test makes sure no near-miss
  ;; co-exists alongside the canonical — across BOTH emit-sources AND
  ;; doc-sources (drift in either is a vocabulary-drift bug).
  (doseq [{:keys [key]} canonical-markers
          [server files] pins/all-source-files
          variant       (pins/near-miss-variants key)
          rel           files]
    (testing (str server " — " rel " — near-miss " variant)
      (is (not (str/includes? (fx/read-source rel) variant))
          (str "Found near-miss variant " variant " for " key
               " in " server "/" rel
               " — this is a vocabulary-drift bug. The canonical "
               "form is " (pins/marker-key->literal key))))))

;; ---------------------------------------------------------------------------
;; JS-vs-Malli `ReFrame2PairOverflowBody` cross-encoding sanity (rf2-0zqox).
;;
;; `test/live-re-frame2-pair-overflow.cjs` hand-rolls `assertOverflowBody` as a JS
;; re-encoding of `ReFrame2PairOverflowBody`. The two encodings must agree on
;; the same contract — that's the whole point of pinning a vocabulary
;; conformance gate; a drift between the encodings is a vocabulary bug
;; (a marker shape the Malli side considers valid that the JS side
;; rejects, or vice versa). Before this gate landed, divergence could
;; ship silently: a tightening to `ReFrame2PairOverflowBody` (e.g. promoting
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

(def ^:private live-re-frame2-pair-overflow-js-rel
  "Relative path to the hand-rolled JS assertion. Single source of truth
  — drift here surfaces against the slurp below."
  "tools/mcp-conformance/test/live-re-frame2-pair-overflow.cjs")

(def ^:private re-frame2-pair-overflow-js-required-grep-markers
  "Substrings the JS `assertOverflowBody` MUST contain to pin every
  required field on `ReFrame2PairOverflowBody`. Each entry is `[malli-field
  js-substring]` — the field for error reporting, the substring as the
  grep target. A field added to `ReFrame2PairOverflowBody` MUST add a row
  here; a field removed from `ReFrame2PairOverflowBody` MUST remove a row.
  Drift surfaces as a test failure naming the missing field.

  Per rf2-i3ffz F-CORR-2/F-HYG-4 (`live-re-frame2-pair-overflow.cjs` rewrite
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

(deftest js-assertOverflowBody-pins-every-re-frame2-pair-overflow-required-field
  ;; The cross-encoding sanity gate. For every required field on the
  ;; Malli `ReFrame2PairOverflowBody` schema, the JS `assertOverflowBody`
  ;; function MUST carry a substring that asserts the same shape.
  ;; Missing fields trip this gate with the field name in the error.
  (let [js-src (fx/read-source live-re-frame2-pair-overflow-js-rel)]
    (doseq [[field grep-pattern] re-frame2-pair-overflow-js-required-grep-markers]
      (testing (str "JS assertOverflowBody pins field " field)
        (is (str/includes? js-src grep-pattern)
            (str "Field `" field
                 "` (Malli `ReFrame2PairOverflowBody`) is not pinned by the "
                 "JS `assertOverflowBody` in " live-re-frame2-pair-overflow-js-rel
                 ". Looked for substring: " (pr-str grep-pattern)
                 ".\nIf you tightened `ReFrame2PairOverflowBody`, mirror the "
                 "change in the JS assertion; if you loosened it, "
                 "remove the entry from "
                 "`re-frame2-pair-overflow-js-required-grep-markers`."))))))

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

(deftest story-mcp-still-emits-zero-uncontracted-cross-mcp-markers
  ;; Self-documenting tripwire: the day story-mcp adopts a NEW
  ;; cross-MCP marker as an INLINE EMISSION (i.e. one it is NOT
  ;; already contracted for in `canonical-markers/:servers`), this
  ;; test flips RED — at which point the reviewer adds story-mcp to
  ;; the `:servers` set on the affected marker, adds a fixture, and
  ;; extends `server-source-files`. That's the right friction;
  ;; conformance is not free.
  ;;
  ;; Markers story-mcp is ALREADY contracted to emit are skipped — the
  ;; contract IS the green-state. As of rf2-yxgcsz that set is
  ;; `#{:rf.mcp/dedup-table :rf.mcp/overflow}` (story-mcp adopted pair-
  ;; mcp's structural-dedup wire-boundary transform under rf2-90eft and
  ;; its token-cap under rf2-yxgcsz, both via the shared mcp-base
  ;; builders); the gate continues to fire on every OTHER marker.
  ;;
  ;; Comment- and docstring-only mentions are stripped before the
  ;; check (via `strip-comments-and-strings`). story-mcp re-uses
  ;; mcp-base's overflow / elision machinery; its `tools/*.cljc` files
  ;; document `:rf.mcp/overflow` and `:rf.size/large-elided` in
  ;; docstrings without inline-emitting either. Documentation is not
  ;; an emission — this tripwire fires only on bare-code occurrences
  ;; (rf2-xx42k).
  (let [story-files       fx/story-mcp-source-files
        uncontracted-keys (for [{:keys [key servers]} canonical-markers
                                :when (not (contains? servers :story-mcp))]
                            key)]
    (doseq [key uncontracted-keys
            rel story-files]
      (testing (str "story-mcp source " rel " — " key " absence")
        (let [stripped (fx/strip-comments-and-strings (fx/read-source rel))]
          (is (not (str/includes? stripped (pins/marker-key->literal key)))
              (str key " literal found in " rel
                   " (in code, after stripping comments/docstrings).\n"
                   "If story-mcp now emits this marker, update "
                   "`canonical-markers` to include :story-mcp in "
                   ":servers, add a story-mcp fixture, and extend "
                   "`server-source-files`.")))))))

;; ---------------------------------------------------------------------------
;; story-mcp source inventory completeness (rf2-ribu5a).
;;
;; `fx/story-mcp-tool-source-files` is the single inventory the generic
;; near-miss / uncontracted-marker / slot-name sweeps grep. Before
;; rf2-ribu5a it was a HAND-MAINTAINED list that had silently fallen
;; behind the directory: it ran through `recorder.cljc` but omitted
;; `dedup.cljc` (emits the cross-MCP `:rf.mcp/dedup-table` marker) and
;; `cursor.cljc` (routes the cross-MCP `:rf.mcp/cursor-stale` marker) —
;; so a near-miss drift in EITHER file could escape the very gates that
;; exist to catch it (only `cursor.cljc` had a bespoke compensating
;; pin; `dedup.cljc` had none). The inventory is now derived from a
;; filesystem listing; these tests pin that contract so the drift class
;; cannot recur:
;;
;;   1. completeness — the derived inventory equals a fresh directory
;;      listing of `tools/story_mcp/tools/*.cljc` (the historically
;;      omitted files are necessarily present; any future tool file is
;;      swept the moment it lands).
;;   2. participation — `dedup.cljc` (the file with NO bespoke
;;      compensating pin) is in the set the generic sweep iterates, so
;;      its `:rf.mcp/dedup-table` emission and any near-miss are checked
;;      generically, not only indirectly against `mcp-base/vocab.cljc`.
;; ---------------------------------------------------------------------------

(deftest story-mcp-tool-inventory-is-filesystem-complete
  ;; The derived inventory MUST equal a fresh listing of the tools dir —
  ;; a tool file added/removed on disk is reflected with zero list
  ;; maintenance. A divergence here means the derivation drifted from
  ;; the directory (it cannot, by construction — this pins that).
  (let [dir            (io/file fx/repo-root fx/story-mcp-tools-dir)
        fresh-listing  (->> (.listFiles dir)
                            (filter #(.isFile %))
                            (map #(.getName %))
                            (filter #(re-find #"\.cljc$" %))
                            (map #(str fx/story-mcp-tools-dir "/" %))
                            set)]
    (is (= fresh-listing (set fx/story-mcp-tool-source-files))
        (str "story-mcp tool-source inventory diverged from the "
             "filesystem listing of " fx/story-mcp-tools-dir
             ". Derived: " (sort fx/story-mcp-tool-source-files)
             "\nFresh: " (sort fresh-listing)))))

(deftest story-mcp-inventory-includes-historically-omitted-tool-files
  ;; Regression for rf2-ribu5a: the two files the hand list dropped MUST
  ;; be in the inventory. dedup.cljc had NO bespoke compensating pin, so
  ;; its omission was a genuine false-green hole on the generic
  ;; uncontracted-marker / near-miss sweeps.
  (let [inventory (set fx/story-mcp-tool-source-files)]
    (is (contains? inventory
                   "tools/story-mcp/src/re_frame/story_mcp/tools/dedup.cljc")
        "dedup.cljc (emits :rf.mcp/dedup-table) MUST be in the central inventory")
    (is (contains? inventory
                   "tools/story-mcp/src/re_frame/story_mcp/tools/cursor.cljc")
        "cursor.cljc (routes :rf.mcp/cursor-stale) MUST be in the central inventory")))

(deftest dedup-source-participates-in-generic-story-mcp-sweep
  ;; Proves dedup.cljc is actually swept by the GENERIC story-mcp
  ;; uncontracted-marker machinery — `story-mcp-still-emits-zero-
  ;; uncontracted-cross-mcp-markers` iterates `fx/story-mcp-source-files`,
  ;; so dedup.cljc must be a member. Before rf2-ribu5a it was NOT, and —
  ;; unlike cursor.cljc — had NO bespoke compensating pin, so an
  ;; accidental inline emission of any uncontracted canonical marker
  ;; (e.g. `:rf.mcp/summary`) in dedup.cljc would have escaped every
  ;; generic sweep.
  ;;
  ;; The participation is structural (membership in the swept set), NOT
  ;; a literal-presence assertion: dedup.cljc emits its ONE contracted
  ;; marker via the shared `base-vocab/dedup-table-key` SYMBOL — the
  ;; `:rf.mcp/dedup-table` literal as DATA lives only in
  ;; `mcp-base/vocab.cljc` (byte-identical across servers by design,
  ;; same posture as cursor.cljc sourcing its reason from
  ;; `vocab/cursor-stale-reason`). So the file correctly carries the
  ;; literal only in docstrings, which the uncontracted sweep strips
  ;; before grepping — and because `:rf.mcp/dedup-table` IS in
  ;; story-mcp's `:servers`, the sweep skips it for dedup.cljc and fires
  ;; only on genuinely uncontracted markers.
  (let [dedup-rel "tools/story-mcp/src/re_frame/story_mcp/tools/dedup.cljc"]
    (is (some #{dedup-rel} fx/story-mcp-source-files)
        "dedup.cljc must be in the source set the uncontracted-marker sweep iterates")
    ;; Belt-and-braces: dedup.cljc carries NO uncontracted canonical
    ;; marker as inline data today (mirrors the green state the generic
    ;; sweep asserts). If a future edit inline-emits one, BOTH this pin
    ;; and the generic sweep flip RED.
    (let [stripped          (fx/strip-comments-and-strings (fx/read-source dedup-rel))
          uncontracted-keys (for [{:keys [key servers]} canonical-markers
                                  :when (not (contains? servers :story-mcp))]
                              key)]
      (doseq [key uncontracted-keys]
        (is (not (str/includes? stripped (pins/marker-key->literal key)))
            (str key " (uncontracted for story-mcp) found as inline data in "
                 dedup-rel " — would be a cross-MCP vocabulary leak."))))))

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
    :emitters #{:re-frame2-pair-mcp :story-mcp}
    :fixtures {:re-frame2-pair-mcp-trace-window
               {:ok? true :epochs [] :dropped-sensitive 3}
               :re-frame2-pair-mcp-snapshot
               {:ok? true :snapshot {} :dropped-sensitive 1}
               ;; story-mcp emission (rf2-koq5m): `read-failures` /
               ;; `run-variant` / `preview-variant` drop `:sensitive? true`
               ;; assertion records at egress and surface the count via
               ;; `egress/with-indicators` (→ mcp-base envelope helper).
               :story-mcp-read-failures
               {:variant-id :story.button/primary :status :pass
                :total 1 :failures [] :assertions [] :dropped-sensitive 2}}}

   {:slot     :elided-large
    :schema   ElidedLarge
    :emitters #{:re-frame2-pair-mcp :story-mcp}
    :fixtures {:re-frame2-pair-mcp-snapshot
               {:ok? true :snapshot {} :elided-large 2}
               :re-frame2-pair-mcp-get-path
               {:ok? true :exists? true :path [:user :pdf] :value
                {:rf.size/large-elided
                 {:path [:user :pdf]
                  :bytes 102400
                  :type :string
                  :reason :effect
                  :hint "User PDF; fetch via get-path."
                  :handle [:rf.elision/at [:user :pdf]]}}
                :elided-large 1}
               ;; story-mcp emission (rf2-koq5m): `run-variant` /
               ;; `preview-variant` elide classified `:large` /
               ;; over-threshold `:app-db` leaves (EP-0025: the declaration
               ;; rides the commit-plane `:large` effect — `:reason :effect`)
               ;; and count the `:rf.size/large-elided` markers via
               ;; `egress/count-elided` (→ mcp-base `count-elided-markers`).
               :story-mcp-run-variant
               {:status :pass :frame :story.button/primary
                :app-db {:blob {:rf.size/large-elided
                                {:path [:blob] :bytes 102400 :type :string
                                 :reason :effect :hint nil
                                 :handle [:rf.elision/at [:blob]]}}}
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

  Both servers route through a single centralised emit-path that
  delegates to the shared mcp-base helper
  (`re-frame.mcp-base.envelope/with-indicators`), so the parity gate
  pins the two literals at the per-server helper location:

  - re-frame2-pair-mcp: `wire.cljs` `with-indicators` (rf2-dfk28); the
    literals live in its docstring + delegation. Per-tool routing is
    pinned in detail by `indicator_field_test.clj`.
  - story-mcp (rf2-koq5m): `egress.cljc` `with-indicators` +
    `count-elided` — the centralised egress helper the
    `run-variant` / `preview-variant` / `read-failures` payload
    builders thread through. `tools_test.clj` exercises the live
    emission end-to-end."
  {:re-frame2-pair-mcp ["tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/wire.cljs"]
   :story-mcp ["tools/story-mcp/src/re_frame/story_mcp/tools/egress.cljc"]})

(deftest envelope-slot-parity-across-emitting-servers
  ;; MUST-level pin (Conventions rf2-2499j, Spec 009 §Indicator field
  ;; on tool responses): every server that emits one slot MUST emit
  ;; the other. The round-2 alignment audit (rf2-zjqh8) caught
  ;; re-frame2-pair-mcp emitting only `:dropped-sensitive`; this gate locks
  ;; in the parity so the regression can't return silently. As of
  ;; rf2-koq5m story-mcp is also a contracted emitter — the gate now
  ;; runs across BOTH servers' helper sources.
  (doseq [server [:re-frame2-pair-mcp :story-mcp]]
    (let [files (get envelope-emitter-source-files server)]
      (is (seq files)
          (str "No source files registered for " server " envelope emit sites."))
      (doseq [rel files]
        (let [src (fx/read-source rel)]
          (testing (str server " " rel " — :dropped-sensitive literal")
            (is (str/includes? src ":dropped-sensitive")
                (str ":dropped-sensitive literal missing from " rel)))
          (testing (str server " " rel " — :elided-large literal")
            (is (str/includes? src ":elided-large")
                (str ":elided-large literal missing from " rel
                     " — parity break per Conventions rf2-2499j."))))))))

(deftest story-mcp-routes-envelope-through-the-centralised-helper
  ;; rf2-koq5m: story-mcp adopted the envelope-indicator parity. The
  ;; centralised emit-path is `egress/with-indicators` (delegating to
  ;; the shared mcp-base helper) + `egress/count-elided` (delegating to
  ;; `count-elided-markers`). This pin asserts those helpers exist AND
  ;; that the tree-walking tools route their payload through the
  ;; centralised egress epilogue — the structural guarantee that the
  ;; omit-when-zero MUST lives in one place, mirroring
  ;; `indicator_field_test.clj`'s pair-mcp routing pin.
  ;;
  ;; rf2-c2wbp folded the dual-coded `(edn-result (with-indicators
  ;; payload {:dropped d :elided (count-elided payload)}))` epilogue —
  ;; previously inlined verbatim at the three live-state read sites
  ;; (dev/preview-variant, testing/run-variant, testing/read-failures) —
  ;; into a single `egress/result-with-indicators` helper, alongside
  ;; `with-indicators` + `count-elided` in `egress.cljc`. The tools now
  ;; route through `egress/result-with-indicators`, which itself routes
  ;; through `egress/with-indicators`: the emit-path is MORE centralised,
  ;; not less. This pin follows the routing into that helper rather than
  ;; greping each tool body for the now-folded `with-indicators` literal.
  (let [egress-rel "tools/story-mcp/src/re_frame/story_mcp/tools/egress.cljc"
        egress-src (fx/read-source egress-rel)]
    (testing "egress.cljc defines the centralised with-indicators helper"
      (is (str/includes? egress-src "(defn with-indicators")
          (str "`with-indicators` helper missing from " egress-rel)))
    (testing "egress.cljc delegates to the shared mcp-base envelope helper"
      (is (str/includes? egress-src "base-envelope/with-indicators")
          (str egress-rel " must delegate to "
               "`re-frame.mcp-base.envelope/with-indicators` — the emit-path "
               "MUST stay centralised in mcp-base (rf2-koq5m / rf2-ee38b.19).")))
    (testing "egress.cljc defines count-elided over the mcp-base walker"
      (is (str/includes? egress-src "base-elision/count-elided-markers")
          (str egress-rel " must reuse "
               "`re-frame.mcp-base.elision/count-elided-markers` for the "
               ":elided-large count (rf2-koq5m).")))
    (testing "egress.cljc defines the result-with-indicators epilogue helper"
      (is (str/includes? egress-src "(defn result-with-indicators")
          (str "`result-with-indicators` helper missing from " egress-rel
               " — the live-state read tools route their payload through it "
               "(rf2-c2wbp).")))
    (testing "result-with-indicators routes through the centralised with-indicators emit-path"
      (is (str/includes? egress-src "(with-indicators payload")
          (str egress-rel "'s `result-with-indicators` must thread its payload "
               "through `with-indicators` — the omit-when-zero MUST stays on "
               "the single centralised emit-path (rf2-koq5m / rf2-c2wbp)."))))
  (doseq [rel ["tools/story-mcp/src/re_frame/story_mcp/tools/testing.cljc"
               "tools/story-mcp/src/re_frame/story_mcp/tools/dev.cljc"]]
    (testing (str rel " — routes payload through egress/result-with-indicators")
      (is (str/includes? (fx/read-source rel) "egress/result-with-indicators")
          (str rel " does not route its payload through "
               "`egress/result-with-indicators` — the centralised egress "
               "epilogue (which threads through `egress/with-indicators`) is "
               "the structural contract for the omit-when-zero MUST "
               "(rf2-koq5m / rf2-c2wbp).")))))
