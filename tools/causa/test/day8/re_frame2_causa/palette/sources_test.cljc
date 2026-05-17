(ns day8.re-frame2-causa.palette.sources-test
  "Tests for the palette source aggregator (rf2-wm7z4).

  Pure-data CLJC. Covers:

  - per-source item shape (the contract the view + events depend on)
  - build-index dedup on [:source :id]
  - rank scoring: boost + recency-bonus add to fuzzy score; cap order
  - popoutable? gate
  - empty-query mode keeps every item and orders by boost+recency"
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-causa.palette.sources :as sources]))

;; ---- fixture inputs -----------------------------------------------------

(def sample-panels
  [{:id :event-detail :label "Event detail"}
   {:id :trace        :label "Trace"}
   {:id :causality    :label "Causality"}])

(def sample-trace-buffer
  ;; oldest → newest order; recency-rank 0 sits at the end
  [{:id 100 :op :event/handled :event-id [:user/login]}
   {:id 101 :op :event/handled :event-id [:user/logout]}
   {:id 102 :op :event/dispatched :event-id [:cart/add 42]}
   {:id 103 :op :trace/note}            ;; filtered — not an event op
   {:id 104 :op :event/handled :event-id [:cart/remove]}])

(def sample-frames [:rf/default :rf/causa :app/main])

(def sample-handlers
  [{:id :user/login    :kind :event :file "user.cljs" :line 12}
   {:id :user/profile  :kind :sub   :doc "Profile sub" :file "u.cljs" :line 30}
   {:id :http/fetch    :kind :fx    :file "http.cljs" :line 8}])

;; ---- per-source shape ---------------------------------------------------

(deftest panel-items-shape
  (let [items (sources/panel-items sample-panels)]
    (is (= 3 (count items)))
    (is (every? #(= :panel (:source %)) items))
    (is (every? #(string? (:label %)) items))
    (is (every? #(string? (:icon %)) items))
    (is (every? #(vector? (:action %)) items))
    (is (every? #(false? (:popout? %)) items)
        "panels are not popoutable in Phase 1")
    (is (= [:palette/select-panel :event-detail]
           (-> items first :action)))))

(deftest recent-event-items-skips-non-event-ops
  (let [items (sources/recent-event-items sample-trace-buffer)]
    (is (= 4 (count items))
        "buffer has 5 rows but one is :trace/note which is filtered out")
    (is (every? #(= :recent-event (:source %)) items))
    (is (every? #(true? (:popout? %)) items)
        "recent events should pop out into event-detail when Ctrl+Enter")))

(deftest recent-event-recency-rank-puts-latest-first
  (let [items (sources/recent-event-items sample-trace-buffer)
        latest (first (filter #(zero? (:recency-rank %)) items))]
    (is (some? latest))
    (is (= "[:cart/remove]" (:label latest))
        "the most-recently-pushed event sits at rank 0")))

(deftest recent-event-cap
  (let [big-buffer (vec
                     (for [i (range 50)]
                       {:id i :op :event/handled :event-id [:noise i]}))
        items      (sources/recent-event-items big-buffer 10)]
    (is (= 10 (count items)))
    (is (= 0 (:recency-rank (first
                              (filter #(zero? (:recency-rank %)) items)))))))

(deftest frame-items-excludes-causa
  (let [items (sources/frame-items sample-frames)
        ids   (set (map :id items))]
    (is (contains? ids :rf/default))
    (is (contains? ids :app/main))
    (is (not (contains? ids :rf/causa))
        "switching focus to :rf/causa is not a meaningful palette op")))

(deftest handler-items-include-meta
  (let [items (sources/handler-items sample-handlers)
        login (first (filter #(= :user/login (second (:id %))) items))]
    (is (some? login))
    (is (= "user.cljs:12" (:hint login)))
    (is (true? (:popout? login)))))

(deftest setting-items-include-density-toggle
  (let [items (sources/setting-items)
        toggle (first (filter #(= :density-toggle (:id %)) items))]
    (is (some? toggle))
    (is (= [:palette/cycle-density] (:action toggle)))))

(deftest command-items-include-core-verbs
  (let [items   (sources/command-items)
        ids     (set (map :id items))]
    (is (contains? ids :clear-trace-buffer))
    (is (contains? ids :reset-suppressed-counters))
    (is (contains? ids :open-popout))
    (is (contains? ids :close-palette))))

;; ---- build-index --------------------------------------------------------

(deftest build-index-includes-every-source
  (let [index   (sources/build-index
                  {:panels             sample-panels
                   :trace-buffer       sample-trace-buffer
                   :frame-ids          sample-frames
                   :handlers           sample-handlers})
        sources (set (map :source index))]
    (is (contains? sources :command))
    (is (contains? sources :panel))
    (is (contains? sources :setting))
    (is (contains? sources :recent-event))
    (is (contains? sources :frame))
    (is (contains? sources :handler))))

(deftest build-index-dedups-on-source-id
  (let [dup-panels [{:id :trace :label "Trace"}
                    {:id :trace :label "Trace (dup)"}]
        index      (sources/build-index {:panels dup-panels})
        traces     (filter #(and (= :panel (:source %))
                                 (= :trace (:id %))) index)]
    (is (= 1 (count traces))
        "first :panel :trace wins, duplicates drop")))

(deftest build-index-defaults-empty-inputs
  (let [index (sources/build-index {})]
    (is (vector? index))
    (is (pos? (count index))
        "commands + settings ship even with no host data")
    (is (every? #(some? (:source %)) index))))

;; ---- ranking ------------------------------------------------------------

(deftest rank-empty-query-keeps-everything-orders-by-boost
  (let [index   (sources/build-index
                  {:panels sample-panels
                   :trace-buffer sample-trace-buffer})
        results (sources/rank index "" 100)]
    (is (= (count index) (count results))
        "empty query keeps every item")
    (is (>= (-> results first :score) (-> results last :score))
        "results are sorted highest-score first")))

(deftest rank-non-matching-query-returns-empty
  (let [index   (sources/build-index {:panels sample-panels})
        results (sources/rank index "zzzzz")]
    (is (empty? results))))

(deftest rank-prefers-commands-over-panels-on-collision
  ;; "cl" matches "Clear trace buffer" (command) AND "Causality" (panel)
  ;; in label fragments. Command boost (40) > panel boost (30), so the
  ;; command should win when fuzzy scores are comparable.
  (let [index   (sources/build-index {:panels sample-panels})
        results (sources/rank index "cl")
        top     (first results)]
    (is (= :command (:source top)))
    (is (= :clear-trace-buffer (:id top)))))

(deftest rank-respects-limit
  (let [index   (sources/build-index
                  {:panels sample-panels :trace-buffer sample-trace-buffer})
        results (sources/rank index "" 3)]
    (is (= 3 (count results)))))

(deftest rank-recency-boosts-latest-event
  ;; Two events whose labels both fuzzy-match the query; the more
  ;; recent one should score higher because of the recency bonus.
  (let [buf [{:id 1 :op :event/handled :event-id [:foo]}
             {:id 2 :op :event/handled :event-id [:foo]}]
        index   (sources/recent-event-items buf)
        results (sources/rank index "foo" 10)
        scores  (map :score results)]
    (is (>= (first scores) (last scores))
        "the higher-ranked (more recent) match scores ≥ the older one")))

;; ---- popoutable? --------------------------------------------------------

(deftest popoutable?-respects-opt-in
  (is (true?  (sources/popoutable? {:popout? true})))
  (is (false? (sources/popoutable? {:popout? false})))
  (is (false? (sources/popoutable? {}))
      "default: items are not popoutable unless they say so"))
