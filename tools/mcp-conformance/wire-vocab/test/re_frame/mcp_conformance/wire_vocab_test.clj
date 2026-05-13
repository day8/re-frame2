(ns re-frame.mcp-conformance.wire-vocab-test
  "Cross-MCP wire-vocabulary conformance (rf2-j2z7o).

  Three MCP servers ship under `tools/`: pair2-mcp, story-mcp, and
  causa-mcp (spec-only today). The first and the third share a
  reserved cross-server **wire vocabulary** — namespaced map keys an
  agent recognises identically across every server:

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
  - `:rf.elision/at`        — size-elision fetch-handle tag
                              (same)

  story-mcp does NOT currently emit any of these markers — it operates
  on small, structured story/variant metadata and stays under the
  wire-cap by construction. The story-mcp `tools.cljc` namespaces its
  own vocabulary under `:rf.story/*`, `:rf.assert/*`, `:rf.error/*`
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

  The sibling `tools/mcp-conformance/test/end-to-end-*.js` files
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
  grep step against `tools/causa-mcp/spec/Principles.md` cover the
  vocabulary today; when impl lands, a follow-up bead extends this
  test to grep `tools/causa-mcp/src/` and add live-emission fixtures
  if their shape diverges from the spec snippets."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [malli.core      :as m]
            [malli.error     :as me]))

;; ---------------------------------------------------------------------------
;; Locate the repo root from the test's classpath. The test runs from
;; `tools/mcp-conformance/wire-vocab/`; the repo root is three levels
;; up. We resolve it from `*file*` at load time so the test is
;; CWD-agnostic (CI runs the test-runner from various locations).
;; ---------------------------------------------------------------------------

(def ^:private repo-root
  "Absolute path to the repo root, derived from this file's location.
  `wire_vocab_test.clj` lives at
  `tools/mcp-conformance/wire-vocab/test/re_frame/mcp_conformance/`.
  Walk up six levels to reach the repo root."
  (let [this-file (io/file (.getPath (io/resource "re_frame/mcp_conformance/wire_vocab_test.clj")))]
    (-> this-file
        .getParentFile                                      ; .../mcp_conformance/
        .getParentFile                                      ; .../re_frame/
        .getParentFile                                      ; .../test/
        .getParentFile                                      ; .../wire-vocab/
        .getParentFile                                      ; .../mcp-conformance/
        .getParentFile                                      ; .../tools/
        .getParentFile                                      ; <repo-root>
        .getAbsolutePath)))

(defn- read-source
  "Slurp a source file inside the repo. `rel-path` is a string path
  segment relative to the repo root, using `/` as the separator. The
  test fails loudly (via `slurp`'s default IOException) if the path
  doesn't resolve — that's the right signal: a source file under
  conformance was moved or removed."
  [rel-path]
  (slurp (io/file repo-root rel-path)))

;; ---------------------------------------------------------------------------
;; Canonical schemas. Single source of truth.
;;
;; Each schema is the cross-MCP-server contract for one wire marker.
;; The marker is **always a single-key map** keyed by the reserved
;; keyword; the value is a body conforming to the per-marker schema
;; below. Agents pattern-match on the top-level reserved key — that
;; rule is what makes the vocabulary cross-server.
;; ---------------------------------------------------------------------------

(def OverflowBody
  "`{:rf.mcp/overflow {...}}` body — the token-budget overflow marker.

  Per causa-mcp Principles §\"1. Token budget cap\" and pair2-mcp
  `tools.cljs/overflow-payload`. The body carries the cap that was
  hit, an `:cap-tokens` or `:cap` cap value (pair2-mcp uses
  `:cap-tokens`; the spec example uses `:cap`), the over-budget
  token count, a tool-specific next-step `:hint`, and a `:limit`
  sentinel.

  pair2-mcp's payload uses `:cap-tokens` / `:token-count` (snake-cased
  with hyphens, no underscores) and adds `:tool` for the offending
  tool name. causa-mcp's spec example uses `:cap` / `:would-be` plus
  a `:continuation` block with `:cursor` + `:next-args`. The
  conformance schema permits both — the load-bearing claim is the
  top-level marker key and the kebab-case child names. Cap renames
  (`cap-tokens` vs `cap_tokens`) trip the schema."
  [:map
   {:closed false}
   [:limit [:enum :reached]]                                   ;; pair2-mcp
   [:tool   {:optional true} [:or :string :keyword]]           ;; pair2-mcp
   [:cap-tokens   {:optional true} :int]                       ;; pair2-mcp form
   [:cap          {:optional true} :int]                       ;; causa-mcp form
   [:token-count  {:optional true} :int]                       ;; pair2-mcp form
   [:would-be     {:optional true} :int]                       ;; causa-mcp form
   [:hint         {:optional true} [:or :string :keyword]]
   [:continuation {:optional true}
    [:map
     [:cursor    {:optional true} [:or :string :nil]]
     [:next-args {:optional true} [:maybe :map]]]]])

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
   [:reason  [:enum :declared :schema :runtime-flagged]]
   [:hint    [:maybe :string]]
   [:handle  [:tuple [:= :rf.elision/at] [:vector :any]]]
   [:digest  {:optional true} :string]])

(def ElisionMarker
  "`{:rf.size/large-elided ElisionMarkerBody}` — the wrapper shape."
  [:map [:rf.size/large-elided ElisionMarkerBody]])

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
    ;; represent two distinct emission shapes — declared/string vs
    ;; runtime-flagged/map-with-digest.
    :servers  #{:pair2-mcp :causa-mcp}
    :fixtures {:pair2-mcp-declared
               {:rf.size/large-elided
                {:path   [:user :uploaded-pdf]
                 :bytes  102400
                 :type   :string
                 :reason :declared
                 :hint   "User-uploaded PDF; fetch via get-path."
                 :handle [:rf.elision/at [:user :uploaded-pdf]]}}
               :pair2-mcp-runtime-with-digest
               {:rf.size/large-elided
                {:path   [:cofx :db]
                 :bytes  524288
                 :type   :map
                 :reason :runtime-flagged
                 :hint   nil
                 :handle [:rf.elision/at [:cofx :db]]
                 :digest "sha256:deadbeefcafef00d"}}}}])

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

;; ---------------------------------------------------------------------------
;; Source-text vocabulary pin. The literal marker key MUST appear in
;; each contracted server's source/spec; near-miss variants (snake_case,
;; pluralised, mis-pluralised) MUST NOT appear. A rename in the
;; framework or in either server surfaces here — the schema is one
;; gate, the literal-occurrence pin is the second.
;; ---------------------------------------------------------------------------

(def ^:private server-source-files
  "Files we grep for marker literals, per server. The lists are
  hand-curated — adding a new emission surface to a server requires
  extending this map (which is the right friction: the conformance
  contract knows where each server's wire boundary lives)."
  {:pair2-mcp ["tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs"
               "tools/pair2-mcp/spec/Principles.md"
               "tools/pair2-mcp/spec/003-Tool-Catalogue.md"]
   :causa-mcp ["tools/causa-mcp/spec/Principles.md"
               "tools/causa-mcp/spec/DESIGN-RATIONALE.md"]
   ;; story-mcp does not currently emit any cross-MCP wire markers
   ;; (it uses its own :rf.story/* + :rf.assert/* + :rf.error/*
   ;; vocabularies). When story-mcp adopts a marker, add a fixture
   ;; AND extend this list — the test enforces both.
   :story-mcp []})

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

(deftest marker-literal-appears-in-every-contracted-server-source
  (doseq [{:keys [key servers]} canonical-markers
          server                servers]
    (testing (str "marker " key " literal in " server " sources")
      (let [literal (marker-key->literal key)
            files   (get server-source-files server)]
        (is (seq files)
            (str "No source files registered for " server
                 " — extend `server-source-files`."))
        (is (some (fn [rel]
                    (str/includes? (read-source rel) literal))
                  files)
            (str "Literal " literal " missing from " server
                 " sources: " files))))))

(deftest no-near-miss-variants-appear-in-any-server-source
  ;; Defence-in-depth: a rename to a near-miss form (e.g. snake_case)
  ;; would slip past the literal-presence test if the canonical form
  ;; ALSO still appears somewhere. This test makes sure no near-miss
  ;; co-exists alongside the canonical.
  (doseq [{:keys [key]} canonical-markers
          [server files] server-source-files
          variant       (near-miss-variants key)
          rel           files]
    (testing (str server " — " rel " — near-miss " variant)
      (is (not (str/includes? (read-source rel) variant))
          (str "Found near-miss variant " variant " for " key
               " in " server "/" rel
               " — this is a vocabulary-drift bug. The canonical "
               "form is " (marker-key->literal key))))))

;; ---------------------------------------------------------------------------
;; Server-coverage pin. The set of servers each marker is contracted
;; against is the *current* state; this test prints it on `--verbose`
;; so a reviewer sees the shape. It also asserts the only servers we
;; reference are the three known servers — a typo in a `:servers` set
;; surfaces here.
;; ---------------------------------------------------------------------------

(def ^:private known-servers #{:pair2-mcp :story-mcp :causa-mcp})

(deftest server-references-are-all-known
  (doseq [{:keys [key servers]} canonical-markers]
    (testing (str "marker " key " — :servers values")
      (is (every? known-servers servers)
          (str "Unknown server in :servers for " key ": "
               (remove known-servers servers))))))

(deftest story-mcp-still-emits-zero-cross-mcp-markers
  ;; Self-documenting tripwire: the day story-mcp adopts ANY of the
  ;; cross-MCP markers, this test flips RED — at which point the
  ;; reviewer adds story-mcp to the `:servers` set on the affected
  ;; marker, adds a fixture, and extends `server-source-files`. That's
  ;; the right friction; conformance is not free.
  (let [story-files ["tools/story-mcp/src/re_frame/story_mcp/tools.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/protocol.cljc"]]
    (doseq [{:keys [key]} canonical-markers
            rel           story-files]
      (testing (str "story-mcp source " rel " — " key " absence")
        (is (not (str/includes? (read-source rel) (marker-key->literal key)))
            (str key " literal found in " rel
                 ".\nIf story-mcp now emits this marker, update "
                 "`canonical-markers` to include :story-mcp in "
                 ":servers, add a story-mcp fixture, and extend "
                 "`server-source-files`."))))))

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
;; (`count-elided-markers`) lives next to them. Consumers import the
;; ns to keep the key bytes byte-identical across servers.
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
                  :reason :declared
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
  mention the slots without emitting them."
  {:pair2-mcp ["tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs"]
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
      (let [src (read-source rel)]
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
  (let [story-files ["tools/story-mcp/src/re_frame/story_mcp/tools.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/protocol.cljc"]
        slots       [":dropped-sensitive" ":elided-large"]]
    (doseq [rel  story-files
            slot slots]
      (testing (str "story-mcp source " rel " — " slot " absence")
        (is (not (str/includes? (read-source rel) slot))
            (str slot " literal found in " rel
                 ".\nIf story-mcp now walks a tree-typed payload, "
                 "the OTHER envelope slot MUST land in the same commit "
                 "(Conventions rf2-2499j MUST-level parity). Update "
                 "`envelope-emitter-source-files` and "
                 "`envelope-indicator-slots`."))))))
