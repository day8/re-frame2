(ns re-frame.story-mcp.tools.dedup-test
  "Unit tests for the structural-dedup wire-boundary transform
  (rf2-90eft) — JVM-side mirror of pair-mcp's `dedup_test.cljs`
  (rf2-obpa9).

  Per `tools/story-mcp/spec/Principles.md` §Structural dedup at the
  wire boundary, every tool's `:structuredContent` payload is passed
  through `day8/de-dupe` before the wire-cap check. Repeated subtrees
  collapse into a flat cache map keyed by `de-dupe.cache/cache-N`
  namespaced symbols; the agent host reconstructs via
  `de-dupe.core/expand`.

  Tests pin the public helpers directly from their owning namespaces:
  `tools.dedup/empty-payload?`, `tools.dedup/dedup-value`,
  `tools.dedup/dedup-expand`, `tools.wire-pipeline/apply-dedup`. A rename or
  signature change surfaces as a failing test rather than a silent
  contract drift.

  `:dedup` MCP-arg normalisation lives on the shared
  `re-frame.mcp-base.args/parse-boolean` table-driven parser (rf2-c4fmh)
  — coverage is in `mcp-base`'s args tests."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame.story-mcp.tools.wire-pipeline :as wire-pipeline]
            [re-frame.story-mcp.tools.dedup :as dedup]
            [re-frame.story-mcp.tools.result :as result]
            [re-frame.story-mcp.tools.registry :as registry]))

;; ---------------------------------------------------------------------------
;; empty-payload? — the no-op guard.
;; ---------------------------------------------------------------------------

(deftest empty-payload-nil-is-empty
  (is (true? (dedup/empty-payload? nil))))

(deftest empty-payload-empty-collections-are-empty
  (is (true? (dedup/empty-payload? [])))
  (is (true? (dedup/empty-payload? {})))
  (is (true? (dedup/empty-payload? #{})))
  (is (true? (dedup/empty-payload? '()))))

(deftest empty-payload-scalars-are-empty
  ;; Scalars can't be deduped — the no-op guard catches them.
  (is (true? (dedup/empty-payload? 42)))
  (is (true? (dedup/empty-payload? :keyword)))
  (is (true? (dedup/empty-payload? "string")))
  (is (true? (dedup/empty-payload? true))))

(deftest empty-payload-non-empty-collections-fire-dedup
  (is (false? (dedup/empty-payload? [1 2 3])))
  (is (false? (dedup/empty-payload? {:a 1})))
  (is (false? (dedup/empty-payload? #{:x})))
  (is (false? (dedup/empty-payload? '(1 2)))))

;; ---------------------------------------------------------------------------
;; dedup-value — the wire-boundary wrap.
;; ---------------------------------------------------------------------------

(deftest dedup-disabled-passes-through
  ;; opt-out: caller asks for the raw payload.
  (let [payload [{:a 1 :b 2} {:a 1 :b 2}]]
    (is (= payload (dedup/dedup-value payload false)))))

(deftest dedup-empty-payload-passes-through
  ;; Empty / scalar inputs skip wrapping — the cache-of-one would
  ;; be a wire-size loss for trivial values.
  (is (nil? (dedup/dedup-value nil true)))
  (is (= [] (dedup/dedup-value [] true)))
  (is (= {} (dedup/dedup-value {} true)))
  (is (= 42 (dedup/dedup-value 42 true))))

(deftest dedup-repeated-subtree-collection-emits-marker
  ;; A non-empty collection WITH a repeated subtree is a genuine dedup
  ;; opportunity, so the wrap fires. (A non-empty collection with NO
  ;; repeats stays raw — see `dedup-no-repeat-collection-stays-raw`;
  ;; mcp-base's `no-substitutions?` skips the wrapper there.)
  (let [shared  {:big :subtree}
        payload [shared shared]
        wrapped (dedup/dedup-value payload true)]
    (is (map? wrapped))
    (is (contains? wrapped :rf.mcp/dedup-table))
    (is (map? (:rf.mcp/dedup-table wrapped))
        "the table itself is a hash-map keyed by namespaced symbols")))

(deftest dedup-no-repeat-collection-stays-raw
  ;; The corrected wire contract (rf2-fwaolt): a non-empty collection
  ;; with no repeated subtrees deduplicates to a one-entry root-only
  ;; cache whose wrapped shape is strictly larger than the input, so
  ;; `dedup-value` returns it RAW rather than growing the wire.
  (let [payload [{:a 1} {:b 2}]]
    (is (= payload (dedup/dedup-value payload true))
        "no dedup opportunity ⇒ verbatim passthrough, not a dedup-table wrap")))

(deftest dedup-marker-key-is-the-cross-mcp-vocabulary
  ;; The marker key matches the cross-MCP §5 (Structural dedup):
  ;; `{:rf.mcp/dedup-table ...}`. Agents that learned the slot on a
  ;; sibling server see the same slot here.
  (let [wrapped (dedup/dedup-value [{:a 1} {:a 1}] true)]
    (is (= [:rf.mcp/dedup-table] (vec (keys wrapped))))
    (is (= base-vocab/dedup-table-key (first (keys wrapped)))
        "the marker key is sourced from `re-frame.mcp-base.vocab/dedup-table-key` — cross-MCP byte-identical with pair-mcp")))

;; ---------------------------------------------------------------------------
;; Round-trip: dedup → expand → identity.
;; ---------------------------------------------------------------------------

(deftest round-trip-simple-shared-map
  (let [shared {:big "common" :keys [:a :b :c]}
        payload [{:id 1 :payload shared}
                 {:id 2 :payload shared}
                 {:id 3 :payload shared}]
        wrapped (dedup/dedup-value payload true)
        restored (dedup/dedup-expand wrapped)]
    (is (= payload restored))))

(deftest round-trip-already-expanded-is-noop
  ;; A payload that was never deduped (caller passed `dedup false`)
  ;; round-trips identity through expand.
  (let [payload [{:a 1} {:b 2}]]
    (is (= payload (dedup/dedup-expand payload)))))

(deftest round-trip-nested-shared-subtrees
  (let [big-db (into {} (for [i (range 100)]
                          [(keyword (str "k" i))
                           {:v (str "value-" i)
                            :meta {:tags [:tag1 :tag2 :tag3]}}]))
        records (vec (for [i (range 10)]
                       {:id     i
                        :db     big-db
                        :meta   {:tags [:tag1 :tag2 :tag3]}}))
        wrapped (dedup/dedup-value records true)
        restored (dedup/dedup-expand wrapped)]
    (is (= records restored))))

(deftest round-trip-empty-collections-inside-payload
  (let [payload [{:items [] :state {}} {:items [] :state {}}]
        wrapped (dedup/dedup-value payload true)
        restored (dedup/dedup-expand wrapped)]
    (is (= payload restored))))

(deftest round-trip-deeply-nested-uniform-records
  ;; Stress: 50 records each carrying the same nested structure.
  (let [record {:cart {:items [{:sku "A" :qty 1}
                               {:sku "B" :qty 2}]
                       :total 30}
                :user {:id 7 :name "alice"}
                :ui {:loading? false :error nil}}
        payload (vec (repeat 50 record))
        wrapped (dedup/dedup-value payload true)
        restored (dedup/dedup-expand wrapped)]
    (is (= payload restored))
    (is (= 50 (count restored)))))

;; ---------------------------------------------------------------------------
;; Reduction-ratio sanity. The bead requires a non-trivial ratio on a
;; realistic story-mcp fixture; we assert against the current run-
;; variant shape (`:app-db` + `:rendered-hiccup` + `:snapshot` carrying
;; the same large nested map) since that's the wire surface this work
;; targets. The deduper is shape-agnostic — it collapses repeated big-db
;; refs regardless of the surrounding verdict keys.
;; ---------------------------------------------------------------------------

(deftest reduction-ratio-run-variant-shape
  ;; A realistic story-mcp tool return: `run-variant`'s structured
  ;; payload re-keys the same `:app-db` value into three slots
  ;; (`:app-db`, `:rendered-hiccup` carries it as `[:value <db>]`, and
  ;; `:snapshot` carries it as the snapshot body). The structural
  ;; deduper collapses those three references into one.
  (let [big-db (into {} (for [i (range 256)]
                          [(keyword (str "k" i))
                           (apply str (repeat 256 \x))]))
        payload {:frame           :story.cart/full
                 :app-db          big-db
                 :rendered-hiccup [:div.cart {:data-state big-db}]
                 :snapshot        {:body big-db}
                 :assertions      [{:assertion :rf.assert/path-equals
                                    :passed?   true
                                    :expected  big-db
                                    :actual    big-db}]
                 :elapsed-ms      42
                 :lifecycle       :ready
                 :status          :pass}
        raw-size (count (pr-str payload))
        wrapped (dedup/dedup-value payload true)
        wrapped-size (count (pr-str wrapped))]
    (testing "wrapped payload is much smaller than the raw structure"
      (is (< wrapped-size raw-size)
          (str "wrapped >= raw — measurement: raw=" raw-size
               "chars deduped=" wrapped-size "chars"))
      ;; Five references to big-db; dedup should compress aggressively.
      ;; ≥50% is the conservative floor pair-mcp uses; the realistic
      ;; story-mcp shape clears it comfortably.
      (is (< wrapped-size (* 0.5 raw-size))
          (str "Deduped size (" wrapped-size
               ") should be < 50% of raw (" raw-size
               "). Ratio: " (/ wrapped-size raw-size 1.0))))
    (testing "round-trip still reconstructs the full payload"
      (let [restored (dedup/dedup-expand wrapped)]
        (is (= payload restored))))))

;; ---------------------------------------------------------------------------
;; Edge cases per the bead.
;; ---------------------------------------------------------------------------

(deftest edge-case-empty-payload-is-noop
  (is (nil? (dedup/dedup-value nil true)))
  (is (= [] (dedup/dedup-value [] true))))

(deftest edge-case-no-repeated-structure
  ;; "payload with no repeated structure (table empty)" — the cache
  ;; ships only the root entry; round-trip still exact.
  (let [payload [{:a 1} {:b 2} {:c 3}]
        wrapped (dedup/dedup-value payload true)
        restored (dedup/dedup-expand wrapped)]
    (is (= payload restored))))

(deftest edge-case-one-big-repeated-subtree
  ;; "payload that's one big repeated subtree (table has 1 entry)" —
  ;; the cache compresses well; round-trip still exact.
  (let [shared (into {} (for [i (range 100)]
                          [(keyword (str "k" i)) i]))
        payload (vec (repeat 20 shared))
        wrapped (dedup/dedup-value payload true)
        restored (dedup/dedup-expand wrapped)]
    (is (= payload restored))
    (is (= 20 (count restored)))
    (is (every? #(= shared %) restored))))

;; ---------------------------------------------------------------------------
;; Wire-boundary integration — `wire-pipeline/apply-dedup` is the wrapper that
;; lifts `dedup-value` onto the story-mcp result-envelope shape.
;; ---------------------------------------------------------------------------

(deftest apply-dedup-passes-result-through-when-disabled
  (let [payload {:a 1 :b [{:k 1} {:k 1}]}
        result  (result/text-result (result/pr-edn payload) payload)
        out     (wire-pipeline/apply-dedup result false)]
    (is (= result out)
        "disabled dedup must be a strict no-op on the envelope")))

(deftest apply-dedup-passes-result-through-when-empty-structured-content
  ;; The empty-payload short-circuit propagates: nil / empty structured
  ;; content rides through unchanged.
  (let [out (wire-pipeline/apply-dedup {:content [{:type "text" :text "hi"}]
                              :structuredContent {}} true)]
    (is (= {} (:structuredContent out)))))

(deftest apply-dedup-rewrites-both-slots-consistently
  ;; The load-bearing wire-boundary invariant: BOTH `:structuredContent`
  ;; AND `:content[*].text` get the deduped payload, so the cap step
  ;; sees the post-dedup size on both slots (rf2-mzndx).
  (let [shared  {:big "value" :tags [:a :b :c]}
        payload [{:id 1 :data shared} {:id 2 :data shared} {:id 3 :data shared}]
        result  (result/text-result (result/pr-edn payload) payload)
        out     (wire-pipeline/apply-dedup result true)
        sc      (:structuredContent out)
        text    (-> out :content first :text)]
    (testing "structuredContent is wrapped under the dedup-table marker"
      (is (contains? sc :rf.mcp/dedup-table))
      (is (= payload (dedup/dedup-expand sc))
          "round-trip restores the original payload"))
    (testing "the text slot mirrors the deduped structured payload"
      (is (= text (result/pr-edn sc))
          "the text slot is re-stringified from the deduped structured payload — both slots ride the same wire shape"))))

(deftest apply-dedup-preserves-sibling-slots
  ;; Anything the handler set on the envelope (e.g. `:isError`) must
  ;; survive the dedup rewrite. The wire-boundary transform is
  ;; structural-content-only.
  (let [shared  {:k :v}
        payload [shared shared]
        result  (assoc (result/text-result (result/pr-edn payload) payload)
                       :_sibling :passes-through)
        out     (wire-pipeline/apply-dedup result true)]
    (is (= :passes-through (:_sibling out)))))

;; ---------------------------------------------------------------------------
;; Eligibility gate — descriptors carry `:dedup-eligible? true` for the
;; three surfaces that benefit (preview-variant, run-variant,
;; record-as-variant); every other tool ignores the wire-boundary
;; dedup transform. Pin via the registry-load.
;; ---------------------------------------------------------------------------

(deftest descriptor-dedup-eligibility-matches-the-documented-set
  ;; Per `tools/story-mcp/spec/Principles.md` §Structural dedup at the
  ;; wire boundary, dedup is applied selectively to surfaces where
  ;; repeated subtrees dominate the wire cost. Mirrors pair-mcp's
  ;; selective `:dedup` knob assignment in `descriptors_data.cljs`.
  (let [eligible (->> registry/tool-registry
                      (filter :dedup-eligible?)
                      (map :name)
                      set)]
    (is (= #{"preview-variant" "run-variant" "record-as-variant"} eligible)
        (str "dedup-eligible set drifted; if extending the contract, "
             "update Principles.md §Structural dedup AND the canonical "
             "list documented here AND in tools.wire-pipeline/invoke-tool's "
             "docstring. Found: " eligible))))

(deftest dedup-eligible-tools-carry-dedup-slot-on-input-schema
  ;; The `:dedup-eligible?` flag is consumed by `wire-pipeline/invoke-tool` (the
  ;; dispatch boundary). The `:inputSchema.:properties.:dedup` slot is
  ;; consumed by the agent host (`tools/list`) so it knows the knob
  ;; exists. The two MUST stay in lock-step — eligibility without the
  ;; descriptor slot is invisible to agents, descriptor slot without
  ;; eligibility is a lying advertisement.
  (doseq [{:keys [name dedup-eligible? inputSchema]} registry/tool-registry]
    (testing (str "tool " name)
      (if dedup-eligible?
        (is (contains? (:properties inputSchema) :dedup)
            (str name " is :dedup-eligible? true but missing the "
                 ":dedup property on its input schema — wrap the "
                 "properties map in `schemas/with-dedup`."))
        (is (not (contains? (:properties inputSchema) :dedup))
            (str name " carries the :dedup property but is NOT "
                 ":dedup-eligible? — `wire-pipeline/invoke-tool` will silently "
                 "ignore the caller's value, which is dishonest. "
                 "Either flip :dedup-eligible? to true or remove "
                 "the `with-dedup` wrap."))))))

;; ---------------------------------------------------------------------------
;; invoke-tool eligibility gate — end-to-end pin that selective dedup
;; actually fires only for opted-in surfaces. Synthesises a one-off
;; eligible + one-off ineligible descriptor via `with-redefs` around
;; `registry/tool-by-name` so the assertion targets the cap-pipeline
;; mechanism rather than a particular tool's domain semantics.
;; ---------------------------------------------------------------------------

(defn- mock-handler [_args]
  (let [payload [{:id 1 :data {:k :shared-value}}
                 {:id 2 :data {:k :shared-value}}
                 {:id 3 :data {:k :shared-value}}]]
    (result/text-result (result/pr-edn payload) payload)))

(deftest invoke-tool-fires-dedup-on-eligible-descriptors
  ;; The end-to-end pin: an eligible descriptor's response carries the
  ;; `:rf.mcp/dedup-table` wrap; an ineligible one's does not. Mocks
  ;; the registry lookup so the assertion stays focused on the gate,
  ;; not on any particular tool's domain.
  (let [eligible-desc   {:name             "test-eligible"
                         :dedup-eligible?  true
                         :handler          mock-handler}
        ineligible-desc {:name            "test-ineligible"
                         :dedup-eligible? false
                         :handler         mock-handler}]
    (testing "eligible descriptor wraps the response under :rf.mcp/dedup-table"
      (with-redefs [registry/tool-by-name (fn [_] eligible-desc)]
        (let [r (wire-pipeline/invoke-tool "test-eligible" {})]
          (is (contains? (:structuredContent r) :rf.mcp/dedup-table)))))
    (testing "ineligible descriptor passes through unwrapped"
      (with-redefs [registry/tool-by-name (fn [_] ineligible-desc)]
        (let [r (wire-pipeline/invoke-tool "test-ineligible" {})]
          (is (not (contains? (:structuredContent r) :rf.mcp/dedup-table))
              "ineligible tools never see the wire-boundary dedup transform"))))
    (testing "eligible descriptor + :dedup false honours the opt-out"
      (with-redefs [registry/tool-by-name (fn [_] eligible-desc)]
        (let [r (wire-pipeline/invoke-tool "test-eligible" {:dedup false})]
          (is (not (contains? (:structuredContent r) :rf.mcp/dedup-table))
              "the per-call :dedup false arg suppresses the wrap even on an eligible tool"))))))
