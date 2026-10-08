(ns re-frame2-pair-mcp.diff-encode-epochs-test
  "The diff-encoded epoch slice as this server composes it: the slice
  transform `re-frame.mcp-base.diff-encode/diff-encode-epochs`, the
  snapshot integration `snapshot-pipeline/diff-encode-epochs-in-snapshot`,
  and the `epochs-mode` arg. The per-record encoding is mcp-base's, and its
  suite pins the round-trip."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]))

(deftest diff-encode-epochs-each-record-self-contained
  ;; Each epoch encodes against ITS OWN :db-before, so a reordered,
  ;; paginated or filtered slice still decodes record by record.
  (let [e1 {:db-before {:a 1} :db-after {:a 2}}
        e2 {:db-before {:b 9} :db-after {:b 9 :c 7}}]
    (is (= [e1 e2]
           (mapv rf.mcp-base.diff-encode/decode-db-after
                 (rf.mcp-base.diff-encode/diff-encode-epochs [e1 e2] :diff))))))

(deftest diff-encode-epochs-empty-vector
  (is (= [] (rf.mcp-base.diff-encode/diff-encode-epochs [] :diff))))

(def ^:private fixture-epoch
  {:epoch-id  :ep-1
   :db-before {:cart {:items [] :total 0} :user {:id 7}}
   :db-after  {:cart {:items [{:sku "A1"}] :total 10} :user {:id 7}}})

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
    (is (= [:db-before :db-before :db-before]
           (for [[_ fmap] enc ep (:epochs fmap)] (-> ep :db-after :rf.mcp/diff-from)))
        "every frame's epochs are diff-encoded")
    (is (= (update-vals fixture-snapshot #(dissoc % :epochs))
           (update-vals enc #(dissoc % :epochs)))
        "other slices pass through unchanged")))

(deftest snapshot-full-mode-passes-through
  (is (= fixture-snapshot
         (pipeline/diff-encode-epochs-in-snapshot fixture-snapshot :full))))

(deftest snapshot-skips-frames-without-epochs-slice
  ;; The :include filter may exclude :epochs. Don't add one.
  (let [snap {:rf/default {:app-db {} :sub-cache {} :machines {}}}]
    (is (not (contains? (:rf/default (pipeline/diff-encode-epochs-in-snapshot snap :diff))
                        :epochs)))))

(deftest parse-epochs-mode-resolution
  ;; An unrecognised value gets the smaller-payload behaviour.
  (doseq [[input expected note]
          [[nil :diff "absent ⇒ diff"]
           ["full" :full "string full"]
           ["garbage" :diff "an unknown string falls back to diff"]]]
    (is (= expected (args/parse-epochs-mode input)) note)))
