(ns day8.re-frame2-causa.panels.reactive-panel-subs-cljs-test
  "Tests for the Reactive panel's pure-data projection
  (rf2-wyvf2 · spec/021 §3).

  Exercises `project-trace-events` over the canonical trace ops that
  landed in PRs #1728 (`:rf.view/rendered`) + #1729 (`:rf.sub/skipped`,
  `:rf.flow/skipped`, `:rf.cascade/captured`) — pure data, no
  re-frame frame required."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.panels.reactive-panel-subs :as subs]))

;; ---- helpers -----------------------------------------------------------

(defn- ev [op payload] {:operation op :payload payload})

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

;; ---- project-trace-events: empty --------------------------------------

(deftest project-empty-trace
  (testing "Empty / nil trace-events projects to zeroed vectors"
    (let [p (subs/project-trace-events [])]
      (is (= [] (:subs-ran p)))
      (is (= [] (:subs-skipped p)))
      (is (= [] (:views-rendered p)))
      (is (= 0 (-> p :counts :subs-ran)))
      (is (= 0 (-> p :counts :subs-skipped)))
      (is (= 0 (-> p :counts :views-rendered))))))

;; ---- project-trace-events: subs/computed -------------------------------

(deftest project-subs-computed
  (testing ":rf.sub/computed events become :subs-ran rows"
    (let [trace [(ev :rf.sub/computed {:sub-id :cart/state})
                 (ev :rf.sub/computed {:sub-id :cart/items})
                 (ev :rf.sub/computed {:sub-id :cart/total})]
          p (subs/project-trace-events trace)]
      (is (= 3 (count (:subs-ran p))))
      (is (= :cart/state (-> p :subs-ran first :sub-id)))
      (is (= 3 (-> p :counts :subs-ran))))))

;; ---- project-trace-events: subs/skipped --------------------------------

(deftest project-subs-skipped
  (testing ":rf.sub/skipped events become :subs-skipped rows (§3.4)"
    (let [trace [(ev :rf.sub/skipped {:sub-id :user/name
                                      :reason :input-unchanged})
                 (ev :rf.sub/skipped {:sub-id :cart/eligibility
                                      :reason :input-unchanged})]
          p (subs/project-trace-events trace)]
      (is (= 2 (count (:subs-skipped p))))
      (is (= :user/name (-> p :subs-skipped first :sub-id)))
      (is (= :input-unchanged (-> p :subs-skipped first :reason))))))

;; ---- project-trace-events: views/rendered ------------------------------

(deftest project-views-rendered
  (testing ":rf.view/rendered events become :views-rendered rows
            with caused-by + file/line attribution"
    (let [trace [(ev :rf.view/rendered
                     {:view-id :checkout/CheckoutButton
                      :file "views/checkout.cljs"
                      :line 88
                      :caused-by-sub :cart/can-submit?
                      :caused-by-paths [[:cart :state]]})]
          p (subs/project-trace-events trace)]
      (is (= 1 (count (:views-rendered p))))
      (let [row (first (:views-rendered p))]
        (is (= :checkout/CheckoutButton (:view-id row)))
        (is (= "views/checkout.cljs" (:file row)))
        (is (= 88 (:line row)))
        (is (= :cart/can-submit? (:caused-by-sub row)))
        (is (= [[:cart :state]] (:caused-by-paths row)))))))

;; ---- project-trace-events: cascade/captured aggregate -----------------

(deftest project-prefers-cascade-captured-counts
  (testing ":rf.cascade/captured payload supplies the canonical
            :counts when present (over the projected vector lengths)"
    (let [trace [(ev :rf.sub/computed {:sub-id :a})
                 (ev :rf.cascade/captured
                     {:subs-ran 99 :subs-skipped 7 :views-rendered 12})]
          p (subs/project-trace-events trace)]
      ;; The vector still reflects what was emitted in this stream:
      (is (= 1 (count (:subs-ran p))))
      ;; But :counts uses the aggregate so the panel's header reads
      ;; the substrate's source of truth.
      (is (= 99 (-> p :counts :subs-ran)))
      (is (= 7  (-> p :counts :subs-skipped)))
      (is (= 12 (-> p :counts :views-rendered))))))

;; ---- project-trace-events: counts fallback ----------------------------

(deftest project-counts-fall-back-to-vector-lengths
  (testing "Without :rf.cascade/captured, :counts mirrors vector lengths"
    (let [trace [(ev :rf.sub/computed {:sub-id :a})
                 (ev :rf.sub/computed {:sub-id :b})
                 (ev :rf.sub/skipped  {:sub-id :c})
                 (ev :rf.view/rendered {:view-id :v})]
          p (subs/project-trace-events trace)]
      (is (= 2 (-> p :counts :subs-ran)))
      (is (= 1 (-> p :counts :subs-skipped)))
      (is (= 1 (-> p :counts :views-rendered))))))

;; ---- project-trace-events: mixed cascade ------------------------------

(deftest project-mixed-cascade-keeps-rows-in-emission-order
  (testing "Multiple ops of the same kw preserve emission order"
    (let [trace [(ev :rf.sub/computed {:sub-id :first})
                 (ev :rf.sub/skipped  {:sub-id :second})
                 (ev :rf.sub/computed {:sub-id :third})
                 (ev :rf.view/rendered {:view-id :v-x})
                 (ev :rf.view/rendered {:view-id :v-y})]
          p (subs/project-trace-events trace)]
      (is (= [:first :third]
             (mapv :sub-id (:subs-ran p))))
      (is (= [:second]
             (mapv :sub-id (:subs-skipped p))))
      (is (= [:v-x :v-y]
             (mapv :view-id (:views-rendered p)))))))
