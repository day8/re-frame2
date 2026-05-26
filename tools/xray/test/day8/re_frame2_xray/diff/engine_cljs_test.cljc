(ns day8.re-frame2-xray.diff.engine-cljs-test
  "Tests for the Editscript-backed diff projection engine
  (`day8.re-frame2-xray.diff.engine`, rf2-n2jig).

  Each test pins one rule from §5.1 of
  `ai/findings/diff-mode-3-key-and-triangle-grammar-2026-05-27.md`
  (as revised in §7 by Mike's pair-debug answers). Pure data → data,
  `.cljc` so the JVM target picks them up too."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.diff.engine :as engine]))

;; ---- R1 modified scalar -------------------------------------------------

(deftest r1-modified-scalar
  (testing "scalar bump produces :modified at the leaf path"
    (let [p (engine/project {:counter 5} {:counter 6})]
      (is (= :modified (engine/op-at p [:counter])))
      (is (= {:op :modified :before 5 :after 6}
             (engine/entry-at p [:counter])))
      ;; Root container should appear with `:children` op since one
      ;; descendant differs.
      (is (= :children (engine/op-at p [])))
      (is (= 1 (engine/change-count-at p []))))))

;; ---- R2 new map key -----------------------------------------------------

(deftest r2-new-map-key
  (testing "wholly new key surfaces as :added at the leaf path"
    (let [p (engine/project {:a 1} {:a 1 :b 2})]
      (is (= :added (engine/op-at p [:b])))
      (is (= 2 (:after (engine/entry-at p [:b]))))
      (is (= :same (engine/op-at p [:a]))))))

(deftest r2-removed-map-key
  (testing "removed key surfaces as :removed at the leaf path"
    (let [p (engine/project {:a 1 :b 2} {:a 1})]
      (is (= :removed (engine/op-at p [:b])))
      (is (= 2 (:before (engine/entry-at p [:b])))))))

;; ---- R5 wholly-new subtree ---------------------------------------------

(deftest r5-wholly-new-subtree
  (testing "subtree where every descendant is :added → parent root logged"
    (let [p (engine/project {:a 1}
                            {:a 1 :flash {:level :ok :text "hi"}})]
      (is (contains? (:wholly-changed-roots p) [:flash])
          "[:flash] should be the wholly-changed root")
      ;; The shallowest wholly-changed ancestor of every descendant of
      ;; [:flash] should be [:flash] itself.
      (is (= [:flash] (engine/wholly-changed-ancestor p [:flash :level])))
      (is (= [:flash] (engine/wholly-changed-ancestor p [:flash :text]))))))

(deftest r5-wholly-removed-subtree
  (testing "subtree where every descendant is :removed → parent root logged"
    (let [p (engine/project {:a 1 :flash {:level :ok :text "hi"}}
                            {:a 1})]
      (is (contains? (:wholly-changed-roots p) [:flash])))))

(deftest r5-mixed-subtree-not-wholly-changed
  (testing "subtree with both :added and :same leaves does NOT reclassify"
    ;; :user has :id (same) AND :name (modified) — not wholly-anything.
    (let [p (engine/project {:user {:id 7 :name "Ada"}}
                            {:user {:id 7 :name "Ada Lovelace"}})]
      (is (not (contains? (:wholly-changed-roots p) [:user]))))))

;; ---- R6 vector shift ----------------------------------------------------

(deftest r6-vector-insert-with-shift
  (testing "vector insert at index 1 — shifted indices get :same-shifted"
    (let [before [:a :b :c :d]
          after  [:a :NEW :b :c :d]
          p (engine/project before after)]
      ;; New element at after-index 1
      (is (= :added (engine/op-at p [1])))
      ;; Shifted elements at after-indices 2,3,4 carry :same-shifted
      ;; with `:was-index` 1,2,3 respectively.
      (is (= :same-shifted (engine/op-at p [2])))
      (is (= 1 (engine/shifted-was-index p [2])))
      (is (= :same-shifted (engine/op-at p [3])))
      (is (= 2 (engine/shifted-was-index p [3])))
      (is (= :same-shifted (engine/op-at p [4])))
      (is (= 3 (engine/shifted-was-index p [4]))))))

(deftest r6-vector-remove-with-shift
  (testing "vector remove — surviving after-indices carry :same-shifted, removed slot lives in :vector-removals"
    (let [before [:a :b :c :d]
          after  [:a :c :d]
          p (engine/project before after)]
      ;; After-index 1 = :c (was :b at before-idx 2). After-index 2 =
      ;; :d (was :c at before-idx 3). Both shifted up by 1.
      (is (= :same-shifted (engine/op-at p [1])))
      (is (= 2 (engine/shifted-was-index p [1])))
      (is (= :same-shifted (engine/op-at p [2])))
      (is (= 3 (engine/shifted-was-index p [2])))
      ;; The removed element :b lives on the :vector-removals channel
      ;; keyed by parent path, NOT on path-ops (the surviving after-
      ;; tree has no stable path identity for it).
      (let [removals (get-in p [:vector-removals []])]
        (is (= 1 (count removals)))
        (is (= {:before-index 1 :before-value :b} (first removals)))))))

;; ---- R7 type change -----------------------------------------------------

(deftest r7-scalar-to-container
  (testing "scalar → map at the same path classifies as :modified"
    (let [p (engine/project {:flash "hi"} {:flash {:level :ok}})]
      (is (= :modified (engine/op-at p [:flash])))
      (is (engine/type-change? p [:flash])))))

(deftest r7-container-to-scalar
  (testing "map → scalar at the same path classifies as :modified"
    (let [p (engine/project {:flash {:level :ok}} {:flash "hi"})]
      (is (= :modified (engine/op-at p [:flash])))
      (is (engine/type-change? p [:flash])))))

(deftest r7-map-to-vector
  (testing "map → vector at the same path classifies as :modified"
    (let [p (engine/project {:items {:a 1}} {:items [1 2 3]})]
      (is (= :modified (engine/op-at p [:items])))
      (is (engine/type-change? p [:items])))))

;; ---- R8 sensitive redaction ---------------------------------------------

(deftest r8-was-redacted-now-visible
  (testing "before sentinel + after real value → :modified with :before side"
    (let [p (engine/project {:secret :rf/redacted}
                            {:secret "real-value"})]
      (is (= :modified (engine/op-at p [:secret])))
      (is (= :before (engine/redaction-side p [:secret]))))))

(deftest r8-was-visible-now-redacted
  (testing "before real value + after sentinel → :modified with :after side"
    (let [p (engine/project {:secret "real-value"}
                            {:secret :rf/redacted})]
      (is (= :modified (engine/op-at p [:secret])))
      (is (= :after (engine/redaction-side p [:secret]))))))

(deftest r8-two-sided-redacted-is-same
  (testing "both sides redacted (v1 scope) → :same (no false-positive change)"
    (let [p (engine/project {:secret :rf/redacted}
                            {:secret :rf/redacted})]
      (is (= :same (engine/op-at p [:secret]))))))

;; ---- Container ops + change count chip (R3 input) ----------------------

(deftest r3-change-count-chip-input
  (testing "[N∆] chip count reflects total non-same descendants"
    (let [p (engine/project {:user {:id 7 :name "Ada" :role "engineer"}}
                            {:user {:id 7 :name "Ada Lovelace" :role "lead"}})]
      ;; Two leaves modified under :user → count 2.
      ;; Including the [:user] ancestor itself counted via the
      ;; container-ancestors walk: each non-same leaf increments every
      ;; ancestor. With 2 leaves the [:user] container reaches 2.
      (is (= 2 (engine/change-count-at p [:user]))))))

;; ---- Flat-rows shape ----------------------------------------------------

(deftest flat-rows-feeds-pure-diff-mode
  (testing "flat-rows projection feeds the :diff (pure list) mode"
    (let [p (engine/project {:counter 5
                             :user {:id 7 :name "Ada"}
                             :legacy-flag true}
                            {:counter 6
                             :user {:id 7 :name "Ada Lovelace"}
                             :flash {:level :ok}})
          rows (:flat-rows p)
          paths (set (map :path rows))
          op-at-path (fn [path]
                       (some (fn [r] (when (= path (:path r)) (:op r)))
                             rows))]
      ;; Four canonical changes: counter modified + user/name modified
      ;; + legacy-flag removed + flash added (wholly-new → per-leaf
      ;; rows expand to flash/level).
      (is (contains? paths [:counter]))
      (is (contains? paths [:user :name]))
      (is (contains? paths [:legacy-flag]))
      (is (contains? paths [:flash :level]))
      (is (= :modified (op-at-path [:counter])))
      (is (= :removed (op-at-path [:legacy-flag]))))))

;; ---- Empty diff edge-case ----------------------------------------------

(deftest empty-diff-when-before-equals-after
  (testing "identical before+after produces empty projection"
    (let [v {:counter 5 :user {:id 7}}
          p (engine/project v v)]
      (is (= {} (:path-ops p)))
      (is (= {} (:container-ops p)))
      (is (= [] (:flat-rows p)))
      (is (= #{} (:wholly-changed-roots p))))))

;; ---- Deep nested change-------------------------------------------------

(deftest deep-nested-change
  (testing "change at depth 5+ marks every ancestor as :children"
    (let [deep-path [:a :b :c :d :e]
          before    (assoc-in {} deep-path 1)
          after     (assoc-in {} deep-path 2)
          p         (engine/project before after)]
      (is (= :modified (engine/op-at p deep-path)))
      (is (= :children (engine/op-at p [:a])))
      (is (= :children (engine/op-at p [:a :b])))
      (is (= :children (engine/op-at p [:a :b :c])))
      (is (= :children (engine/op-at p [:a :b :c :d]))))))

;; ---- Sanity — Editscript imports cleanly under the build ---------------

(deftest editscript-import-sanity
  (testing "engine/project handles the §2 canonical example without throwing"
    (let [before {:counter 5
                  :user {:id 7 :name "Ada"}
                  :legacy-flag true}
          after  {:counter 6
                  :user {:id 7 :name "Ada Lovelace"}
                  :flash {:level :ok :text "Order placed"}}
          p (engine/project before after)]
      (is (map? p))
      (is (vector? (:flat-rows p)))
      (is (= :modified (engine/op-at p [:counter])))
      (is (= :modified (engine/op-at p [:user :name])))
      (is (= :removed  (engine/op-at p [:legacy-flag])))
      (is (contains? (:wholly-changed-roots p) [:flash])))))
