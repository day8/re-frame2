(ns re-frame2-pair-mcp.diff-encode-epochs-test
  "Unit tests for the diff-encoded epoch slice.

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` mechanism (Diff-encoded
  epoch slice), every `:rf/epoch-record` shipped over the wire has
  its `:db-after` replaced with a path-keyed structural diff against
  its own `:db-before` by default. The transform lives in
  `re-frame.mcp-base.diff-encode` at the cross-MCP boundary — consumers
  require it DIRECTLY (there is no `tools.dedup` pass-through facade);
  the `epochs-mode` wire-arg normaliser lives in
  `re-frame2-pair-mcp.tools.args/parse-epochs-mode` and the snapshot
  pipeline composes the transform via
  `tools.snapshot-pipeline/diff-encode-epochs-in-snapshot`.

  Tests pin the surfaces this server composes:
  `re-frame.mcp-base.diff-encode/diff-encode-epochs` (every record
  decodes on its own, and an empty slice stays an empty vector),
  `tools.snapshot-pipeline/diff-encode-epochs-in-snapshot` and
  `tools.args/parse-epochs-mode`. The per-record encoding
  (`diff-encode-db-after`, `decode-db-after` and the patch primitives
  under them) is mcp-base's own, and its suite pins the round-trip
  property on both the JVM and CLJS lanes.

  Live end-to-end coverage runs against a real shadow-cljs build
  with a populated `epoch-history`; this file pins the pure CLJS
  transforms."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]))

;; ---------------------------------------------------------------------------
;; The fixture epoch — one committed cart change.
;; ---------------------------------------------------------------------------

(def ^:private fixture-epoch
  {:epoch-id     :ep-1
   :frame        :rf/default
   :committed-at 1234567890
   :event-id     :cart/add
   :trigger-event [:cart/add {:sku "A1"}]
   :db-before    {:cart {:items [] :total 0}
                  :user {:id 7}}
   :db-after     {:cart {:items [{:sku "A1"}] :total 10}
                  :user {:id 7}}})

;; ---------------------------------------------------------------------------
;; diff-encode-epochs — the slice-level transform.
;; ---------------------------------------------------------------------------

(deftest diff-encode-epochs-each-record-self-contained
  ;; Independence property: each epoch encodes against ITS OWN
  ;; :db-before; reordering / pagination / filtering of the slice
  ;; doesn't break decode.
  (let [e1 {:db-before {:a 1} :db-after {:a 2}}
        e2 {:db-before {:b 9} :db-after {:b 9 :c 7}}
        enc (rf.mcp-base.diff-encode/diff-encode-epochs [e1 e2] :diff)]
    (is (= e1 (rf.mcp-base.diff-encode/decode-db-after (first enc))))
    (is (= e2 (rf.mcp-base.diff-encode/decode-db-after (second enc))))
    ;; Reverse order — still decodable.
    (let [reversed (reverse enc)]
      (is (= e2 (rf.mcp-base.diff-encode/decode-db-after (first reversed))))
      (is (= e1 (rf.mcp-base.diff-encode/decode-db-after (second reversed)))))))

(deftest diff-encode-epochs-empty-vector
  (is (= [] (rf.mcp-base.diff-encode/diff-encode-epochs [] :diff)))
  (is (= [] (rf.mcp-base.diff-encode/diff-encode-epochs [] :full))))

;; ---------------------------------------------------------------------------
;; diff-encode-epochs-in-snapshot — the snapshot-tool integration.
;; ---------------------------------------------------------------------------

(def ^:private fixture-snapshot
  {:rf/default {:app-db    {:k :v}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    [fixture-epoch fixture-epoch]
                :traces    []}
   :stories    {:app-db    {:k2 :v2}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    [{:db-before {:foo 1} :db-after {:foo 2}}]
                :traces    []}})

(deftest snapshot-diff-mode-encodes-every-frames-epochs
  (let [enc (pipeline/diff-encode-epochs-in-snapshot fixture-snapshot :diff)]
    (testing ":epochs slice transformed on every frame"
      (doseq [[_fid fmap] enc]
        (doseq [ep (:epochs fmap)]
          (is (= :db-before (-> ep :db-after :rf.mcp/diff-from))))))
    (testing "other slices pass through unchanged"
      (is (= {:k :v} (-> enc :rf/default :app-db)))
      (is (= {} (-> enc :rf/default :sub-cache)))
      (is (= [] (-> enc :rf/default :traces))))))

(deftest snapshot-full-mode-passes-through
  (is (= fixture-snapshot
         (pipeline/diff-encode-epochs-in-snapshot fixture-snapshot :full))))

(deftest snapshot-skips-frames-without-epochs-slice
  ;; The :include filter may exclude :epochs. Don't add one.
  (let [snap {:rf/default {:app-db {} :sub-cache {} :machines {}}}
        enc  (pipeline/diff-encode-epochs-in-snapshot snap :diff)]
    (is (not (contains? (:rf/default enc) :epochs)))))

(deftest snapshot-non-map-passes-through
  (is (nil? (pipeline/diff-encode-epochs-in-snapshot nil :diff)))
  (is (= :not-a-snap (pipeline/diff-encode-epochs-in-snapshot :not-a-snap :diff))))

;; ---------------------------------------------------------------------------
;; parse-epochs-mode — MCP-arg normalisation.
;; ---------------------------------------------------------------------------

(deftest parse-epochs-mode-resolution
  ;; Least-surprise on the budget-sensitive default: an unrecognised
  ;; value gets the smaller-wire-payload behaviour, not the larger.
  (doseq [[input expected note]
          [[nil :diff "absent ⇒ diff"]
           ["diff" :diff "string diff"]
           ["full" :full "string full"]
           [:diff :diff "keyword diff"]
           [:full :full "keyword full"]
           ["garbage" :diff "an unknown string falls back to diff"]
           [42 :diff "a number falls back to diff"]
           [:other :diff "an unknown keyword falls back to diff"]]]
    (is (= expected (args/parse-epochs-mode input)) note)))
