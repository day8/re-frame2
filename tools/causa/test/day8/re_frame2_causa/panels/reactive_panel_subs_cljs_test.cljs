(ns day8.re-frame2-causa.panels.reactive-panel-subs-cljs-test
  "Tests for the Reactive panel's pure-data projection
  (rf2-wyvf2 · spec/021 §3).

  Exercises `project-record` over the substrate's structured
  `:rf/epoch-record` projections (`:sub-runs`, `:renders`) plus the
  flow counts tallied from `:trace-events` — pure data, no re-frame
  frame required.

  The canonical op / projection shapes (Spec 009 §:op-type vocabulary +
  spec/018):

  - `:sub-runs` entry → `{:sub-id _ :query-v _ :recomputed? bool}`;
    `:recomputed?` splits subs-ran (true) from memo-hit skips (false).
  - `:renders`  entry → `{:render-key [view-id idx] ...}`.
  - flow ops on `:trace-events` → `:rf.flow/computed` / `:rf.flow/skip`.

  (The earlier suite asserted the *buggy* contract — it grepped raw
  `:trace-events` for `:rf.sub/computed` / `:rf.sub/skipped`, names the
  substrate never emits — so it stayed green while the panel showed
  zero subs ran.)"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.panels.reactive-panel-subs :as subs]))

;; ---- helpers -----------------------------------------------------------

(defn- sub-run [sub-id recomputed?]
  {:sub-id sub-id :query-v [sub-id] :recomputed? recomputed?})

(defn- render [view-id]
  {:render-key [view-id 0] :triggered-by nil :elapsed-ms nil})

(defn- flow-ev [op] {:operation op})

;; ---- focused-epoch-record ---------------------------------------------

(deftest focused-epoch-record-finds-by-id
  (testing "focused-epoch-record returns the record matching :epoch-id"
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (= {:epoch-id :b} (subs/focused-epoch-record history :b))))))

(deftest focused-epoch-record-falls-back-to-head-when-no-match
  (testing "Missing :epoch-id (LIVE) or evicted id → head record"
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (= {:epoch-id :c} (subs/focused-epoch-record history nil)))
      (is (= {:epoch-id :c} (subs/focused-epoch-record history :missing))))))

(deftest focused-epoch-record-empty-history-nil
  (testing "Empty history returns nil"
    (is (nil? (subs/focused-epoch-record [] :anything)))
    (is (nil? (subs/focused-epoch-record nil :anything)))))

;; ---- project-record: empty --------------------------------------------

(deftest project-empty-record
  (testing "Empty / nil record projects to zeroed vectors + counts"
    (doseq [r [nil {}]]
      (let [p (subs/project-record r)]
        (is (= [] (:subs-ran p)))
        (is (= [] (:subs-skipped p)))
        (is (= [] (:views-rendered p)))
        (is (= 0 (-> p :counts :subs-ran)))
        (is (= 0 (-> p :counts :subs-skipped)))
        (is (= 0 (-> p :counts :views-rendered)))
        (is (= 0 (-> p :counts :flows-recomputed)))
        (is (= 0 (-> p :counts :flows-skipped)))))))

;; ---- project-record: subs ran (:recomputed? true) ---------------------

(deftest project-subs-ran
  (testing ":sub-runs entries with :recomputed? true become :subs-ran rows"
    (let [record {:sub-runs [(sub-run :cart/state true)
                             (sub-run :cart/items true)
                             (sub-run :cart/total true)]}
          p (subs/project-record record)]
      (is (= 3 (count (:subs-ran p))))
      (is (= :cart/state (-> p :subs-ran first :sub-id)))
      (is (= 3 (-> p :counts :subs-ran)))
      (is (= 0 (-> p :counts :subs-skipped))))))

;; ---- project-record: subs skipped (memo hits) -------------------------

(deftest project-subs-skipped
  (testing ":sub-runs entries with :recomputed? false become :subs-skipped rows (§3.4)"
    (let [record {:sub-runs [(sub-run :user/name false)
                             (sub-run :cart/eligibility false)]}
          p (subs/project-record record)]
      (is (= 2 (count (:subs-skipped p))))
      (is (= :user/name (-> p :subs-skipped first :sub-id)))
      (is (= 2 (-> p :counts :subs-skipped)))
      (is (= 0 (-> p :counts :subs-ran))))))

;; ---- project-record: split a mixed :sub-runs vector -------------------

(deftest project-subs-split-by-recomputed
  (testing "A mixed :sub-runs vector splits ran vs skipped, order preserved"
    (let [record {:sub-runs [(sub-run :first true)
                             (sub-run :second false)
                             (sub-run :third true)]}
          p (subs/project-record record)]
      (is (= [:first :third] (mapv :sub-id (:subs-ran p))))
      (is (= [:second]       (mapv :sub-id (:subs-skipped p)))))))

;; ---- project-record: views rendered -----------------------------------

(deftest project-views-rendered
  (testing ":renders entries lift :view-id from :render-key, preserve :render-key"
    (let [record {:renders [(render :checkout/CheckoutButton)
                           (render :cart/Summary)]}
          p (subs/project-record record)]
      (is (= 2 (count (:views-rendered p))))
      (is (= [:checkout/CheckoutButton :cart/Summary]
             (mapv :view-id (:views-rendered p))))
      (is (= [:checkout/CheckoutButton 0] (-> p :views-rendered first :render-key)))
      (is (= 2 (-> p :counts :views-rendered))))))

;; ---- project-record: flow counts from :trace-events -------------------

(deftest project-flow-counts-from-trace
  (testing "Flow counts tally :rf.flow/computed and :rf.flow/skip (canonical names)"
    (let [record {:trace-events [(flow-ev :rf.flow/computed)
                                (flow-ev :rf.flow/computed)
                                (flow-ev :rf.flow/skip)]}
          p (subs/project-record record)]
      (is (= 2 (-> p :counts :flows-recomputed)))
      (is (= 1 (-> p :counts :flows-skipped))))))

;; ---- project-record: full record composes -----------------------------

(deftest project-full-record
  (testing "All projections compose from one record"
    (let [record {:sub-runs     [(sub-run :a true) (sub-run :b false) (sub-run :c true)]
                  :renders      [(render :v-x) (render :v-y)]
                  :trace-events [(flow-ev :rf.flow/computed)]}
          p (subs/project-record record)]
      (is (= 2 (-> p :counts :subs-ran)))
      (is (= 1 (-> p :counts :subs-skipped)))
      (is (= 2 (-> p :counts :views-rendered)))
      (is (= 1 (-> p :counts :flows-recomputed)))
      (is (= [:v-x :v-y] (mapv :view-id (:views-rendered p)))))))
