(ns re-frame2-pair-mcp.dedup-test
  "Structural dedup as this server applies it: the payload-size win on a
  representative epoch window, and the per-frame snapshot integration
  (`snapshot-pipeline/dedup-epochs-in-snapshot`). The dedup algorithm
  itself is `re-frame.mcp-base.dedup`'s, and its suite pins it."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.test-utils :as tu]))

(deftest reduction-ratio-shared-subtrees
  ;; spec/Principles.md promises a >=50% reduction when diff-encoded
  ;; records share their `:db-before`: 10 epochs over a 256-key app-db.
  (let [big-db (into {} (for [i (range 256)]
                          [(keyword (str "k" i))
                           (apply str (repeat 256 \x))]))
        epochs (vec (for [i (range 10)]
                      (let [path [(keyword (str "k" i))]]
                        {:epoch-id (str "ep-" i)
                         :event-id :touch
                         :db-before big-db
                         :db-after  {:rf.mcp/diff-from :db-before
                                     :sections [{:section-path path
                                                 :section-kind :modified
                                                 :patches [[path :assoc (apply str (repeat 256 \y))]]}]}})))
        raw-size     (count (pr-str epochs))
        wrapped      (rf.mcp-base.dedup/dedup-value epochs true)
        wrapped-size (count (pr-str wrapped))]
    (is (< wrapped-size (* 0.5 raw-size))
        (str "Deduped size (" wrapped-size ") should be < 50% of raw (" raw-size ")"))
    (is (= epochs (tu/dedup-expand wrapped)))))

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

(deftest snapshot-dedup-round-trips-per-frame
  (let [wrapped (pipeline/dedup-epochs-in-snapshot fixture-snapshot true)]
    (is (every? #(contains? (:epochs %) :rf.mcp/dedup-table) (vals wrapped))
        "every frame's :epochs slice is wrapped")
    (is (= fixture-snapshot (update-vals wrapped #(update % :epochs tu/dedup-expand)))
        "expanding :epochs restores the snapshot; other slices pass through")))

(deftest snapshot-dedup-disabled-passes-through
  (is (= fixture-snapshot
         (pipeline/dedup-epochs-in-snapshot fixture-snapshot false))))

(deftest snapshot-dedup-skips-frames-without-epochs-slice
  ;; The :include filter may exclude :epochs. Don't add one.
  (let [snap {:rf/default {:app-db {} :sub-cache {}}}
        wrapped (pipeline/dedup-epochs-in-snapshot snap true)]
    (is (not (contains? (:rf/default wrapped) :epochs)))))
