(ns re-frame2-pair-mcp.dedup-test
  "Unit tests for the structural-dedup wire-boundary transform
  (rf2-obpa9).

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` mechanism (Structural
  dedup), every `:rf/epoch-record` slice and each subscribe-tick
  events vector is passed through `day8/de-dupe` before the wire-cap
  check. Repeated subtrees (notably the per-record `:db-before`
  reference after diff-encoding) collapse into a flat cache map that
  the agent host reconstructs via `de-dupe.core/expand`.

  Tests pin the public helpers directly from their owning namespaces:
  `tools.dedup/empty-payload?`, `tools.dedup/dedup-value`,
  `test-utils/dedup-expand`,
  `tools.snapshot-pipeline/dedup-epochs-in-snapshot`. A rename or
  signature change surfaces as a failing test rather than a silent
  contract drift.

  `:dedup` MCP-arg normalisation lives on the shared table-driven
  parser (`re-frame2-pair-mcp.tools.args/parse-bool-arg`, rf2-c4fmh);
  see `re-frame2-pair-mcp.args-test` for the coverage.

  Live end-to-end coverage runs against a real shadow-cljs build via
  the existing stdio-roundtrip harness; this file pins the pure
  transforms, the round-trip property, the wire shape, and the
  reduction-ratio sanity check."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.test-utils :as tu]))

;; ---------------------------------------------------------------------------
;; empty-payload? — the no-op guard.
;; ---------------------------------------------------------------------------

(deftest empty-payload-nil-is-empty
  (is (true? (dedup/empty-payload? nil))))

(deftest empty-payload-empty-vector-is-empty
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

(deftest dedup-non-empty-collection-emits-marker
  (let [payload [{:a 1} {:b 2}]
        wrapped (dedup/dedup-value payload true)]
    (is (map? wrapped))
    (is (contains? wrapped :rf.mcp/dedup-table))
    (is (map? (:rf.mcp/dedup-table wrapped))
        "the table itself is a hash-map keyed by namespaced symbols")))

(deftest dedup-marker-key-is-the-cross-mcp-vocabulary
  ;; The marker key matches the cross-MCP §5 (Structural dedup):
  ;; `{:rf.mcp/dedup-table ...}`. Agents that learned the slot on a
  ;; sibling server see the same slot here.
  (let [wrapped (dedup/dedup-value [{:a 1} {:a 1}] true)]
    (is (= [:rf.mcp/dedup-table] (vec (keys wrapped))))))

;; ---------------------------------------------------------------------------
;; Round-trip: dedup → expand → identity.
;; ---------------------------------------------------------------------------

(deftest round-trip-simple-shared-map
  (let [shared {:big "common" :keys [:a :b :c]}
        payload [{:id 1 :payload shared}
                 {:id 2 :payload shared}
                 {:id 3 :payload shared}]
        wrapped (dedup/dedup-value payload true)
        restored (tu/dedup-expand wrapped)]
    (is (= payload restored))))

(deftest round-trip-already-expanded-is-noop
  ;; A payload that was never deduped (caller passed `dedup false`)
  ;; round-trips identity through expand.
  (let [payload [{:a 1} {:b 2}]]
    (is (= payload (tu/dedup-expand payload)))))

(deftest round-trip-nested-shared-subtrees
  ;; The load-bearing case: epoch slice where every record carries
  ;; the same large `:db-before`. After dedup → expand the structure
  ;; comes back exactly.
  (let [big-db (into {} (for [i (range 100)]
                          [(keyword (str "k" i))
                           {:v (str "value-" i)
                            :meta {:tags [:tag1 :tag2 :tag3]}}]))
        ;; rf2-qeous: wire shape is sections-per-cluster. Each
        ;; synthetic epoch carries a single-section :db-after.
        epochs (vec (for [i (range 10)]
                      (let [path [(keyword (str "k" i)) :v]]
                        {:epoch-id (str "ep-" i)
                         :db-before big-db
                         :db-after  {:rf.mcp/diff-from :db-before
                                     :sections [{:section-path [(keyword (str "k" i))]
                                                 :section-kind :modified
                                                 :patches [[path :assoc (str "new-" i)]]}]}})))
        wrapped (dedup/dedup-value epochs true)
        restored (tu/dedup-expand wrapped)]
    (is (= epochs restored))))

(deftest round-trip-empty-collections-inside-payload
  (let [payload [{:items [] :state {}} {:items [] :state {}}]
        wrapped (dedup/dedup-value payload true)
        restored (tu/dedup-expand wrapped)]
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
        restored (tu/dedup-expand wrapped)]
    (is (= payload restored))
    (is (= 50 (count restored)))))

;; ---------------------------------------------------------------------------
;; Reduction-ratio sanity: 10-epoch window with shared map structure.
;; The bead requires ≥50% reduction; we assert the actual value with
;; a generous floor because the precise ratio depends on subtree
;; cardinality + map layout.
;; ---------------------------------------------------------------------------

(deftest reduction-ratio-shared-subtrees
  ;; The load-bearing scenario rf2-obpa9 targets: a 10-epoch window
  ;; whose records share their `:db-before` reference. The diff-
  ;; encoder (rf2-1wdzp) already reduced :db-after to a tiny patch
  ;; per record; the deduper now collapses the repeated :db-before.
  (let [;; Build a "big" app-db — 256 keys, each pointing at a 256-char
        ;; string value ⇒ ~80KB pr-str.
        big-db (into {} (for [i (range 256)]
                          [(keyword (str "k" i))
                           (apply str (repeat 256 \x))]))
        ;; 10 epochs, each sharing the same :db-before reference.
        ;; rf2-qeous: sections-per-cluster wire shape.
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
        wrapped (dedup/dedup-value epochs true)
        wrapped-size (count (pr-str wrapped))]
    (testing "wrapped payload is much smaller than the raw vector"
      ;; Silent-on-success (rf2-try1x): the measurement is folded into
      ;; the failing-assertion messages below; agents reading green-run
      ;; output don't burn context on per-test diagnostics.
      (is (< wrapped-size raw-size)
          (str "wrapped >= raw — measurement: raw=" raw-size
               "chars deduped=" wrapped-size "chars"))
      ;; Conservative: ≥50% reduction (the bead's floor). Actual
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
;; Edge cases per the bead.
;; ---------------------------------------------------------------------------

(deftest edge-case-empty-payload-is-noop
  ;; "empty payload (no-op)" — the wrapper short-circuits.
  (is (nil? (dedup/dedup-value nil true)))
  (is (= [] (dedup/dedup-value [] true))))

(deftest edge-case-no-repeated-structure
  ;; "payload with no repeated structure (table empty)" — the cache
  ;; ships only the root entry; round-trip still exact.
  (let [payload [{:a 1} {:b 2} {:c 3}]
        wrapped (dedup/dedup-value payload true)
        restored (tu/dedup-expand wrapped)]
    (is (= payload restored))))

(deftest edge-case-one-big-repeated-subtree
  ;; "payload that's one big repeated subtree (table has 1 entry)" —
  ;; the cache compresses well; round-trip still exact.
  (let [shared (into {} (for [i (range 100)]
                          [(keyword (str "k" i)) i]))
        payload (vec (repeat 20 shared))
        wrapped (dedup/dedup-value payload true)
        restored (tu/dedup-expand wrapped)]
    (is (= payload restored))
    (is (= 20 (count restored)))
    (is (every? #(= shared %) restored))))

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
