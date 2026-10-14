(ns re-frame.mcp-conformance.wire-vocab-test
  "Generic wrapper-marker conformance for the cross-MCP vocabulary.

  `wire-vocab.schemas/canonical-markers` owns the wrapper keys, schemas,
  contracted servers and representative fixtures. This namespace checks
  that every fixture conforms, drives each JVM-reachable emitter live
  against its schema, pins each marker literal in its emit and doc
  sources, and holds the envelope indicator-field slots."
  (:require [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [malli.core      :as m]
            [malli.error     :as me]
            ;; Canonical framework elision builder and classification effect.
            [re-frame.core   :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame  :as rf.frame]
            ;; Schema-validation failure is an independent elision emitter.
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.schemas
             :refer [Overflow DedupTable DiffFromBody ElisionMarker
                     DroppedSensitive ElidedLarge canonical-markers]]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]))

(deftest every-fixture-conforms-to-its-canonical-schema
  (doseq [{:keys [key schema fixtures]} canonical-markers
          [fixture-name fixture-value] fixtures]
    (testing (str "marker " key " — fixture " fixture-name)
      (is (m/validate schema fixture-value)
          (str "Fixture " fixture-name " for " key
               " failed schema validation:\n"
               (me/humanize (m/explain schema fixture-value)))))))

(deftest overflow-empty-body-is-rejected
  ;; Every body field is required; an emit carrying only part of the shape fails.
  (is (not (m/validate Overflow
                       {:rf.mcp/overflow
                        {:limit      :reached
                         :tool       "snapshot"
                         :cap-tokens 5000
                         :hint       "..."}}))
      "re-frame2-pair-shape emit missing :token-count must fail"))

;; Overflow and ElisionMarker are the wrappers a production builder emits
;; whole, so their closedness is what lets the live gates below catch a
;; builder that adds a sibling key.
(deftest wrapper-markers-reject-extra-sibling-key
  (doseq [[schema wrapper] [[Overflow {:rf.mcp/overflow {:limit :reached :token-count 1 :cap-tokens 1
                                                         :tool "snapshot" :hint "..."}}]
                            [ElisionMarker {:rf.size/large-elided {:path [:a] :bytes 1 :type :map
                                                                   :reason :effect :hint nil
                                                                   :handle [:rf.elision/at [:a]]}}]]]
    (is (not (m/validate schema (assoc wrapper :sneaky :key)))
        (str (ffirst wrapper) " MUST be a single key — an extra sibling fails"))))

(deftest diff-from-rejects-extra-sibling-key
  ;; The marker key and `:sections` ride as a closed PAIR.
  (is (not (m/validate DiffFromBody {:rf.mcp/diff-from :db-before :sections [] :sneaky :key}))
      "a diff-from :db-after carrying a key beyond {marker, :sections} MUST fail")
  (is (not (m/validate DiffFromBody {:rf.mcp/diff-from :db-before}))
      "the :sections body slot is required"))

(deftest dedup-table-rejects-rootless-cache
  ;; `expand` and the Node decoder both start at cache-0, so a root-less
  ;; table is un-expandable.
  (is (not (m/validate DedupTable
                       {:rf.mcp/dedup-table
                        {:de-dupe.cache/cache-1 {:event-id :foo}
                         :de-dupe.cache/cache-2 {:event-id :bar}}}))
      "DedupTable must reject a cache missing the de-dupe.cache/cache-0 root."))

(def ^:private node-decoder-root-cache-id
  "The `ROOT_CACHE_ID` literal `lib/dedup-envelope.cjs` decodes from."
  "de-dupe.cache/cache-0")

(deftest dedup-table-emitted-live-by-canonical-encoder
  (let [payload   [{:event-id :user/sign-in  :handler-id :auth}
                   {:event-id :user/sign-in  :handler-id :auth}
                   {:event-id :user/sign-out :handler-id :auth}]
        cache     (rf.mcp-base.dedup/de-dupe-eq payload)
        wrapped   {:rf.mcp/dedup-table cache}]
    (testing "the live-emitted marker validates against the tightened DedupTable schema"
      (is (m/validate DedupTable wrapped)
          (str "Live-emitted dedup-table failed DedupTable validation:\n"
               (me/humanize (m/explain DedupTable wrapped)))))
    (testing "JVM↔Node root agreement: the JSON string form of the root key matches the Node decoder's ROOT_CACHE_ID"
      ;; Cheshire, which the MCP wire uses, writes a namespaced symbol key as
      ;; its full `(str sym)` form.
      (let [root-key-strs (set (map str (keys cache)))]
        (is (contains? root-key-strs node-decoder-root-cache-id)
            (str "The cache's root key, in its on-the-wire string form, "
                 "MUST be " (pr-str node-decoder-root-cache-id)
                 " — the literal the Node decoder (lib/dedup-envelope.cjs "
                 "ROOT_CACHE_ID) requires. Got key string forms: "
                 (pr-str root-key-strs)))))))

(deftest diff-from-marker-emitted-live-by-canonical-encoder
  (let [epoch    {:db-before {:cart {:items [{:sku "abc"}]}
                              :checkout {:state :idle}
                              :tmp 42}
                  :db-after  {:cart {:items [{:sku "abc"} {:sku "xyz"}]}
                              :checkout {:state :review}}
                  :event     [:cart/add-item {:sku "xyz"}]}
        encoded  (rf.mcp-base.diff-encode/diff-encode-db-after epoch)
        db-after (:db-after encoded)]
    (testing "the emitted body validates against the canonical DiffFromBody schema"
      (is (m/validate DiffFromBody db-after)
          (str "Live-emitted :db-after failed DiffFromBody validation:\n"
               (me/humanize (m/explain DiffFromBody db-after)))))))

(deftest overflow-marker-shape-emitted-live-by-canonical-builder
  (let [marker (rf.mcp-base.overflow/overflow-payload
                 {:tool "snapshot" :token-count 6250 :cap 5000
                  :hint "Narrow the scope."})]
    (is (m/validate Overflow marker)
        (str "Live-built overflow marker failed Overflow validation:\n"
             (me/humanize (m/explain Overflow marker))))))

(defn- elision-live-marker
  "Drive `re-frame.elision/elide-wire-value` over `v` with the `large`
  paths classified through the commit-plane `:large` effect, under a fresh
  `:rf/default` frame."
  [large v]
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.elision :reload)
  (rf.elision/clear-warning-cache!)
  (rf.elision/configure! {:rf.egress/threshold-bytes 16384})
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (rf.frame/swap-runtime-db! :rf/default
      (fn [rt] (rf.elision/apply-classification-effects rt {:large (mapv vec large)})))
    (rf.elision/elide-wire-value v)))

(deftest elision-marker-emitted-live-by-canonical-walker
  (let [out    (elision-live-marker
                 [[:user :uploaded-pdf]]
                 {:user {:name "Ada" :uploaded-pdf "<<5MB-blob>>"}})
        marker (get-in out [:user :uploaded-pdf])]
    (is (m/validate ElisionMarker marker)
        (str "Live-emitted elision marker failed ElisionMarker validation "
             "— the canonical schema has drifted from the runtime emitter "
             "(a :reason drift the fixture+grep "
             "layers cannot see):\n"
             (me/humanize (m/explain ElisionMarker marker))))
    (is (= {:reason :effect :path [:user :uploaded-pdf]}
           (select-keys (:rf.size/large-elided marker) [:reason :path])))))

(defn- schema-validation-failure-marker
  "Drive `re-frame.schemas.validate/validate-event!` over a `:large?` slot
  whose value fails validation and return the `:rf.size/large-elided`
  marker substituted into the failure trace's `:value` slot."
  [blob]
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (let [traces (atom [])]
    (rf/register-listener! :trace ::sv (fn [ev] (swap! traces conj ev)))
    (rf.schemas/validate-event! :upload/save [:upload/save {:blob blob}]
                             {:schema [:cat [:= :upload/save]
                                       [:map [:blob {:large? true} :int]]]})
    (rf/unregister-listener! :trace ::sv)
    (-> (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                       @traces))
        :tags :value)))

(deftest schema-validation-failure-marker-emitted-live-validates
  (let [blob   (apply str (repeat 200 "X"))
        marker (schema-validation-failure-marker blob)]
    (testing "the live-emitted validation-failure marker validates against ElisionMarker"
      (is (m/validate ElisionMarker marker)
          (str "Live-emitted schema-validation-failure marker failed "
               "ElisionMarker validation — the schemas-artefact emitter has "
               "drifted from the canonical contract:\n"
               (me/humanize (m/explain ElisionMarker marker)))))
    (testing "the live :reason is the EP-0025 commit-plane classification default, NOT :schema"
      (is (= :effect (get-in marker [:rf.size/large-elided :reason]))
          (str "The validation-failure marker MUST emit :reason :effect "
               "(EP-0025 — the canonical commit-plane classification source). Got: "
               (pr-str (get-in marker [:rf.size/large-elided :reason])))))
    (testing "the large blob survives nowhere verbatim in the marker"
      (is (not (str/includes? (pr-str marker) blob))
          "the whole-value substitution must not re-leak the blob"))))

;; Emit sources are scanned after stripping comments and strings, so a
;; docstring mention cannot satisfy the pin; doc sources are scanned raw.
(deftest marker-literal-appears-in-every-contracted-server-emit-source
  (doseq [{:keys [key servers]} canonical-markers
          server                servers]
    (testing (str "marker " key " literal in " server " emit-sources")
      (let [literal    (rf.mcp-conformance.wire-vocab.source-pins/marker-key->literal key)
            emit-files (get rf.mcp-conformance.wire-vocab.source-pins/emit-source-files server)]
        (is (some (fn [rel]
                    (let [stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings
                                     (rf.mcp-conformance.fixtures/read-source rel))]
                      (str/includes? stripped literal)))
                  emit-files)
            (str "Literal " literal
                 " missing from " server " EMIT-sources " emit-files
                 " (checked AFTER stripping docstrings/comments). "
                 "If the canonical declaration moved, update "
                 "`emit-source-files`."))))))

(deftest marker-literal-appears-in-re-frame2-pair-mcp-doc-sources
  (doseq [{:keys [key servers]} canonical-markers
          :when                 (contains? servers :re-frame2-pair-mcp)]
    (testing (str "marker " key " literal in re-frame2-pair-mcp doc-sources")
      (let [literal   (rf.mcp-conformance.wire-vocab.source-pins/marker-key->literal key)
            doc-files (get rf.mcp-conformance.wire-vocab.source-pins/doc-source-files :re-frame2-pair-mcp)]
        (is (some (fn [rel]
                    (str/includes? (rf.mcp-conformance.fixtures/read-source rel) literal))
                  doc-files)
            (str "Literal " literal
                 " missing from re-frame2-pair-mcp doc-sources " doc-files
                 ". The docs may have re-organised the prose; either "
                 "restore the mention or update `doc-source-files`."))))))

;; `lib/overflow-marker.cjs` re-encodes `ReFrame2PairOverflowBody` for the
;; live overflow gate. Each required field must keep its row there, and the
;; cross-field invariant, which the Malli schema does not model, its check.

(def ^:private live-re-frame2-pair-overflow-js-rel
  "tools/mcp-conformance/lib/overflow-marker.cjs")

(def ^:private re-frame2-pair-overflow-js-required-grep-markers
  "`[malli-field js-substring]` pairs; the substring is the field's
  `REQUIRED_FIELDS` row as the source spells it."
  [[":limit :reached"
    "['limit',       (v) => v === 'reached',          'enum :reached']"]
   [":cap-tokens : int"
    "['cap-tokens',  (v) => Number.isInteger(v),      'int']"]
   [":token-count : int"
    "['token-count', (v) => Number.isInteger(v),      'int']"]
   [":tool : string|keyword"
    "['tool',        (v) => typeof v === 'string',    'string|keyword']"]
   [":hint : string|keyword"
    "['hint',        (v) => typeof v === 'string',    'string|keyword']"]
   ["token-count > cap-tokens invariant"
    "body['token-count'] <= body['cap-tokens']"]])

(deftest js-assertOverflowBody-pins-every-re-frame2-pair-overflow-required-field
  (let [js-src (rf.mcp-conformance.fixtures/read-source live-re-frame2-pair-overflow-js-rel)]
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

;; A marker story-mcp starts emitting must first be contracted for it in
;; `canonical-markers`, with a fixture and a source pin.
(deftest story-mcp-still-emits-zero-uncontracted-cross-mcp-markers
  (let [story-files       rf.mcp-conformance.fixtures/story-mcp-source-files
        uncontracted-keys (for [{:keys [key servers]} canonical-markers
                                :when (not (contains? servers :story-mcp))]
                            key)]
    (doseq [key uncontracted-keys
            rel story-files]
      (testing (str "story-mcp source " rel " — " key " absence")
        (let [stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings (rf.mcp-conformance.fixtures/read-source rel))]
          (is (not (str/includes? stripped (rf.mcp-conformance.wire-vocab.source-pins/marker-key->literal key)))
              (str key " literal found in " rel
                   " (in code, after stripping comments/docstrings).\n"
                   "If story-mcp emits this marker, update "
                   "`canonical-markers` to include :story-mcp in "
                   ":servers, add a story-mcp fixture, and extend "
                   "`emit-source-files` (wire_vocab/source_pins.clj) if it "
                   "does not already pin the marker's owner.")))))))

;; Envelope indicator slots (Conventions §Cross-MCP indicator-field
;; vocabulary). The omit-when-zero MUST makes a present slot always a
;; positive count.

(def envelope-indicator-slots
  "Each unqualified envelope slot with its schema and per-server fixtures."
  [{:slot     :dropped-sensitive
    :schema   DroppedSensitive
    :fixtures {:re-frame2-pair-mcp-trace-window
               {:ok? true :epochs [] :dropped-sensitive 3}
               :re-frame2-pair-mcp-snapshot
               {:ok? true :snapshot {} :dropped-sensitive 1}
               :story-mcp-read-failures
               {:variant-id :story.button/primary :status :pass
                :total 1 :failures [] :assertions [] :dropped-sensitive 2}}}

   {:slot     :elided-large
    :schema   ElidedLarge
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

(deftest envelope-indicator-present-zero-rejected
  (doseq [{:keys [slot schema]} envelope-indicator-slots]
    (testing (str "present-zero " slot " ⇒ schema REJECTS (omit-when-zero, not zero-emit)")
      (is (not (m/validate schema {slot 0}))
          (str "Canonical schema for " slot " ACCEPTED a present zero {" slot " 0} — "
               "the omit-when-zero MUST means a zero count is OMITTED, never "
               "emitted as `" slot " 0`. The schema must be `pos-int?` (not "
               "`nat-int?`) so a helper-bypassing present-zero regression fails "
               "conformance.")))))

;; story-mcp's twin of `indicator_field_test.clj`'s pair-mcp routing pin:
;; the omit-when-zero rule lives on one emit-path in mcp-base.
(deftest story-mcp-routes-envelope-through-the-centralised-helper
  (let [egress-rel "tools/story-mcp/src/re_frame/story_mcp/tools/egress.cljc"
        egress-src (rf.mcp-conformance.fixtures/read-source egress-rel)]
    (testing "egress.cljc delegates to the shared mcp-base envelope helper"
      (is (str/includes? egress-src "rf.mcp-base.envelope/with-indicators")
          (str egress-rel " must delegate to "
               "`re-frame.mcp-base.envelope/with-indicators` — the emit-path "
               "MUST stay centralised in mcp-base.")))
    (testing "egress.cljc defines count-elided over the mcp-base walker"
      (is (str/includes? egress-src "rf.mcp-base.elision/count-elided-markers")
          (str egress-rel " must reuse "
               "`re-frame.mcp-base.elision/count-elided-markers` for the "
               ":elided-large count.")))
    (testing "result-with-indicators routes through the centralised with-indicators emit-path"
      (is (str/includes? egress-src "(with-indicators payload")
          (str egress-rel "'s `result-with-indicators` must thread its payload "
               "through `with-indicators` — the omit-when-zero MUST stays on "
               "the single centralised emit-path."))))
  (doseq [rel ["tools/story-mcp/src/re_frame/story_mcp/tools/testing.cljc"
               "tools/story-mcp/src/re_frame/story_mcp/tools/dev.cljc"]]
    (testing (str rel " — routes payload through egress/result-with-indicators")
      (is (str/includes? (rf.mcp-conformance.fixtures/read-source rel) "egress/result-with-indicators")
          (str rel " does not route its payload through "
               "`egress/result-with-indicators` — the centralised egress "
               "epilogue (which threads through `egress/with-indicators`) is "
               "the structural contract for the omit-when-zero "
               "MUST.")))))
