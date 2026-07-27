(ns re-frame.story-mcp.tools.dedup-test
  "Consumer-integration + payload-ratio coverage for the structural-dedup
  wire-boundary transform (rf2-90eft).

  Per `tools/story-mcp/spec/Principles.md` §Structural dedup at the wire
  boundary, a dedup-eligible tool's `:structuredContent` payload is
  passed through `re-frame.mcp-base.dedup` before the wire-cap check. Repeated
  subtrees collapse into a flat cache map keyed by `de-dupe.cache/cache-N`
  namespaced symbols; the agent host reconstructs via
  `re-frame.mcp-base.dedup/expand`.

  ## What this file pins — and what it deliberately does NOT

  The CANONICAL dedup behaviour (`empty-payload?`, `dedup-value` wrap /
  passthrough / marker shape, round-trip exactness) is asserted ONCE,
  cross-host, in `re-frame.mcp-base.dedup-test` (rf2-ywkiss) — story-mcp
  now requires `re-frame.mcp-base.dedup` DIRECTLY (the `tools.dedup`
  pass-through facade was removed), so re-asserting the same behaviour
  here would just duplicate that suite. What stays here is the coverage
  the base suite CANNOT own:

    - the wire-boundary envelope integration
      (`wire-pipeline/apply-dedup` dual-slot rewrite, sibling-slot
      preservation, the `:dedup-eligible?` gate through
      `wire-pipeline/invoke-tool`);
    - the reduction-ratio (payload-ratio) sanity on a representative
      `run-variant` fixture.

  The test-only inverse (`dedup-expand`) lives in
  `re-frame.story-mcp.test-support` (rf2-ywkiss moved it out of the
  removed production `tools.dedup` namespace); the MCP server never
  inverts the transform at runtime.

  `:dedup` MCP-arg normalisation lives on the shared
  `re-frame.mcp-base.args/parse-boolean` table-driven parser (rf2-c4fmh)
  — coverage is in `mcp-base`'s args tests."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.dedup :as base-dedup]
            [re-frame.story-mcp.test-support :as tsup]
            [re-frame.story-mcp.tools.wire-pipeline :as wire-pipeline]
            [re-frame.story-mcp.tools.result :as result]
            [re-frame.story-mcp.tools.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Reduction-ratio sanity. The bead requires a non-trivial ratio on a
;; realistic story-mcp fixture; we assert against the current run-
;; variant shape (`:app-db` + `:rendered-hiccup` + `:snapshot` carrying
;; the same large nested map) since that's the wire surface this work
;; targets. The deduper is shape-agnostic — it collapses repeated big-db
;; refs regardless of the surrounding verdict keys. This is the
;; consumer's payload-ratio coverage (the base suite pins correctness,
;; not the wire-size win on a realistic story-mcp shape).
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
        wrapped (base-dedup/dedup-value payload true)
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
      (let [restored (tsup/dedup-expand wrapped)]
        (is (= payload restored))))))

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
      (is (= payload (tsup/dedup-expand sc))
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
