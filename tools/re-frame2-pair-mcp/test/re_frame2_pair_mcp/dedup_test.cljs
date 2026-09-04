(ns re-frame2-pair-mcp.dedup-test
  "Consumer-integration + payload-ratio coverage for the structural-dedup
  wire-boundary transform.

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` mechanism (Structural
  dedup), every `:rf/epoch-record` slice
  is passed through `re-frame.mcp-base.dedup` before the wire-cap check.
  Repeated subtrees (notably the per-record `:db-before` reference after
  diff-encoding) collapse into a flat cache map that the agent host
  reconstructs via `re-frame.mcp-base.dedup/expand`.

  ## What this file pins — and what it deliberately does NOT

  The CANONICAL dedup behaviour (`empty-payload?`, `dedup-value` wrap /
  passthrough / marker shape, round-trip exactness, the no-substitutions
  skip) is asserted ONCE, cross-host, in `re-frame.mcp-base.dedup-test`
  (rf2-ywkiss) — this consumer now requires `re-frame.mcp-base.dedup`
  DIRECTLY, so re-asserting the same behaviour here would just duplicate
  that suite. What stays here is the coverage the base suite CANNOT own:

    - the per-frame snapshot integration
      (`snapshot-pipeline/dedup-epochs-in-snapshot`) — pipeline order,
      envelope shape, per-frame round-trip;
    - the reduction-ratio (payload-ratio) sanity on a representative
      epoch-window fixture.

  Live end-to-end coverage runs against a real shadow-cljs build via the
  existing stdio-roundtrip harness."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.test-utils :as tu]))

;; ---------------------------------------------------------------------------
;; Reduction-ratio sanity: 10-epoch window with shared map structure.
;; The contract requires ≥50% reduction; we assert the actual value with
;; a generous floor because the precise ratio depends on subtree
;; cardinality + map layout. This is the consumer's payload-ratio
;; coverage (the base suite pins correctness, not the wire-size win on a
;; realistic pair-mcp epoch shape).
;; ---------------------------------------------------------------------------

(deftest reduction-ratio-shared-subtrees
  ;; The load-bearing scenario: a 10-epoch window whose records share
  ;; their `:db-before` reference. The diff-encoder reduces :db-after to
  ;; a tiny patch per record; the deduper collapses the repeated
  ;; :db-before.
  (let [;; Build a "big" app-db — 256 keys, each pointing at a 256-char
        ;; string value ⇒ ~80KB pr-str.
        big-db (into {} (for [i (range 256)]
                          [(keyword (str "k" i))
                           (apply str (repeat 256 \x))]))
        ;; 10 epochs, each sharing the same :db-before reference.
        ;; Sections-per-cluster wire shape.
        epochs (vec (for [i (range 10)]
                      (let [path [(keyword (str "k" i))]]
                        {:epoch-id (str "ep-" i)
                         :event-id :touch
                         :db-before big-db
                         :db-after  {:rf.mcp/diff-from :db-before
                                     :sections [{:section-path path
                                                 :section-kind :modified
                                                 :patches [[path :assoc (apply str (repeat 256 \y))]]}]}})))
        raw-size (count (pr-str epochs))
        wrapped (rf.mcp-base.dedup/dedup-value epochs true)
        wrapped-size (count (pr-str wrapped))]
    (testing "wrapped payload is much smaller than the raw vector"
      ;; Silent-on-success: the measurement is folded into the
      ;; failing-assertion messages below; agents reading green-run
      ;; output don't burn context on per-test diagnostics.
      (is (< wrapped-size raw-size)
          (str "wrapped >= raw — measurement: raw=" raw-size
               "chars deduped=" wrapped-size "chars"))
      ;; Conservative: ≥50% reduction (the contract floor). Actual
      ;; should be much higher when the same :db-before reference
      ;; rides 10 times.
      (is (< wrapped-size (* 0.5 raw-size))
          (str "Deduped size (" wrapped-size
               ") should be < 50% of raw (" raw-size
               "). Ratio: " (/ wrapped-size raw-size 1.0))))
    (testing "round-trip still reconstructs every epoch"
      (let [restored (tu/dedup-expand wrapped)]
        (is (= epochs restored))))))

;; ---------------------------------------------------------------------------
;; dedup-epochs-in-snapshot — per-frame integration.
;; ---------------------------------------------------------------------------

(def ^:private fixture-snapshot
  {:rf/default {:app-db    {:k :v}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    [{:epoch-id :ep-1
                             :db-before {:cart {:items []}}
                             :db-after  {:rf.mcp/diff-from :db-before :sections []}}
                            {:epoch-id :ep-2
                             :db-before {:cart {:items []}}
                             :db-after  {:rf.mcp/diff-from :db-before :sections []}}]
                :traces    []}
   :stories    {:app-db    {:k2 :v2}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    [{:epoch-id :ep-A
                             :db-before {:foo 1}
                             :db-after  {:rf.mcp/diff-from :db-before
                                         :sections [{:section-path [:foo]
                                                     :section-kind :modified
                                                     :patches [[[:foo] :assoc 2]]}]}}]
                :traces    []}})

(deftest snapshot-dedup-wraps-each-frames-epochs
  (let [wrapped (pipeline/dedup-epochs-in-snapshot fixture-snapshot true)]
    (testing ":epochs slot wrapped on every frame that has one"
      (doseq [[_fid fmap] wrapped]
        (let [eps (:epochs fmap)]
          (is (and (map? eps) (contains? eps :rf.mcp/dedup-table))
              "epochs slice replaced with dedup-table marker"))))
    (testing "other slices pass through unchanged"
      (is (= {:k :v} (-> wrapped :rf/default :app-db)))
      (is (= [] (-> wrapped :rf/default :traces))))))

(deftest snapshot-dedup-disabled-passes-through
  (is (= fixture-snapshot
         (pipeline/dedup-epochs-in-snapshot fixture-snapshot false))))

(deftest snapshot-dedup-skips-frames-without-epochs-slice
  ;; The :include filter may exclude :epochs. Don't add one.
  (let [snap {:rf/default {:app-db {} :sub-cache {}}}
        wrapped (pipeline/dedup-epochs-in-snapshot snap true)]
    (is (not (contains? (:rf/default wrapped) :epochs)))))

(deftest snapshot-dedup-non-map-passes-through
  (is (nil? (pipeline/dedup-epochs-in-snapshot nil true)))
  (is (= :not-a-snap (pipeline/dedup-epochs-in-snapshot :not-a-snap true))))

(deftest snapshot-dedup-round-trips-per-frame
  (let [wrapped (pipeline/dedup-epochs-in-snapshot fixture-snapshot true)
        restored (reduce-kv
                   (fn [m fid fmap]
                     (assoc m fid
                            (if (contains? fmap :epochs)
                              (update fmap :epochs tu/dedup-expand)
                              fmap)))
                   {}
                   wrapped)]
    (is (= fixture-snapshot restored))))
