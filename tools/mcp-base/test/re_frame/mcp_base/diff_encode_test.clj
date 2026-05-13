(ns re-frame.mcp-base.diff-encode-test
  "Tests for the path-keyed structural diff used at the MCP wire
  boundary (rf2-1wdzp, shared across the triplet via rf2-vw4sq)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.diff-encode :as de]))

;; ---------------------------------------------------------------------------
;; collect-patches — direct cases.
;; ---------------------------------------------------------------------------

(deftest collect-patches-empty-when-equal
  (is (= [] (de/collect-patches {:a 1} {:a 1} []))))

(deftest collect-patches-handles-added-key
  (is (= [[[:b] :assoc 2]]
         (de/collect-patches {:a 1} {:a 1 :b 2} []))))

(deftest collect-patches-handles-removed-key
  (is (= [[[:b] :dissoc]]
         (de/collect-patches {:a 1 :b 2} {:a 1} []))))

(deftest collect-patches-handles-changed-leaf
  (is (= [[[:a] :assoc 3]]
         (de/collect-patches {:a 1} {:a 3} []))))

(deftest collect-patches-recurses-into-nested-maps
  (let [a {:user {:name "ada" :age 30}}
        b {:user {:name "ada" :age 31}}]
    (is (= [[[:user :age] :assoc 31]]
           (de/collect-patches a b [])))))

(deftest collect-patches-leaf-replacement-for-shape-mismatch
  (is (= [[[] :assoc [1 2 3]]]
         (de/collect-patches {:a 1} [1 2 3] [])))
  (is (= [[[:a] :assoc [1 2 3]]]
         (de/collect-patches {:a {:b 1}} {:a [1 2 3]} []))))

;; ---------------------------------------------------------------------------
;; apply-patches — round-trips collect-patches.
;; ---------------------------------------------------------------------------

(deftest apply-patches-reverses-collect-patches
  (testing "trivial"
    (let [a {:a 1}
          b {:a 1 :b 2}
          p (de/collect-patches a b [])]
      (is (= b (de/apply-patches a p)))))
  (testing "nested + delete + change"
    (let [a {:user {:name "ada" :age 30} :session :idle}
          b {:user {:name "ada" :age 31 :role :admin}}
          p (de/collect-patches a b [])]
      (is (= b (de/apply-patches a p)))))
  (testing "shape change at root"
    (let [a {:a 1}
          b [1 2 3]
          p (de/collect-patches a b [])]
      (is (= b (de/apply-patches a p))))))

(deftest apply-patches-dissoc-at-root-via-direct-path
  (is (= {:a 1} (de/apply-patches {:a 1 :b 2} [[[:b] :dissoc]]))))

;; ---------------------------------------------------------------------------
;; diff-encode-db-after / decode-db-after — round-trip.
;; ---------------------------------------------------------------------------

(deftest diff-encode-db-after-emits-diff-shape
  (let [epoch    {:db-before {:a 1 :b 2}
                  :db-after  {:a 1 :b 3}}
        encoded  (de/diff-encode-db-after epoch)]
    (is (= :db-before (get-in encoded [:db-after :rf.mcp/diff-from])))
    (is (vector? (get-in encoded [:db-after :patches])))
    (is (= [[[:b] :assoc 3]] (get-in encoded [:db-after :patches])))))

(deftest diff-encode-then-decode-restores-original
  (let [epoch   {:db-before {:user {:name "ada" :age 30}
                             :session :idle}
                 :db-after  {:user {:name "ada" :age 31 :role :admin}}
                 :event     [:user/birthday]}
        encoded (de/diff-encode-db-after epoch)
        decoded (de/decode-db-after encoded)]
    (is (= epoch decoded))))

(deftest diff-encode-db-after-passes-through-when-missing-halves
  (testing "missing db-before"
    (let [epoch {:db-after {:x 1}}]
      (is (= epoch (de/diff-encode-db-after epoch)))))
  (testing "missing db-after"
    (let [epoch {:db-before {:x 1}}]
      (is (= epoch (de/diff-encode-db-after epoch)))))
  (testing "non-map epoch"
    (is (= [1 2 3] (de/diff-encode-db-after [1 2 3])))))

(deftest decode-db-after-passes-through-when-not-a-diff
  ;; Already-full epoch (no marker) decodes to itself.
  (let [epoch {:db-before {:a 1} :db-after {:a 1 :b 2}}]
    (is (= epoch (de/decode-db-after epoch)))))

;; ---------------------------------------------------------------------------
;; diff-encode-epochs — vector form + mode toggle.
;; ---------------------------------------------------------------------------

(deftest diff-encode-epochs-diff-mode-encodes-each-record
  (let [epochs [{:db-before {:a 1} :db-after {:a 2}}
                {:db-before {:b 1} :db-after {:b 2}}]
        out    (de/diff-encode-epochs epochs :diff)]
    (is (= 2 (count out)))
    (is (= :db-before (get-in (first out) [:db-after :rf.mcp/diff-from])))
    (is (= :db-before (get-in (second out) [:db-after :rf.mcp/diff-from])))))

(deftest diff-encode-epochs-full-mode-is-passthrough
  (let [epochs [{:db-before {:a 1} :db-after {:a 2}}]]
    (is (= epochs (de/diff-encode-epochs epochs :full)))))

(deftest diff-encode-epochs-each-record-self-contained
  ;; The slice can be reordered, paginated, or filtered without
  ;; breaking decode — every record carries its own :db-before so it
  ;; round-trips standalone.
  (let [epochs [{:db-before {:a 1} :db-after {:a 2}}
                {:db-before {:b 1} :db-after {:b 2}}]
        out    (de/diff-encode-epochs epochs :diff)
        reversed (vec (reverse out))]
    (doseq [enc reversed]
      (let [dec (de/decode-db-after enc)]
        (is (= (dissoc enc :db-after :patches)
               (dissoc dec :db-after))
            "non-:db-after fields unchanged on decode")))))
