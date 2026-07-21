(ns re-frame.ui.reactive-commit-record-cljs-test
  "rf2-8ds0v (S6 slice a) — the S6 committed-instance record plumbing on the
  REAL ViewCell commit path (Ruling 1 / Option 1A, honest capture; EP-0033 §Two
  evidence layers). A CONNECTED commit mints a fresh MODULE-GLOBAL monotonic
  integer :render-key and publishes the per-commit record onto the cell; a
  never-committed (speculative) render publishes nothing.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM), so the record plumbing is graft-checked on both hosts
  over the plain-atom adapter — the same headless discipline the reconcile
  fixtures use.

  Scope fence: slice a minted the record shape; slice b (rf2-qkq2k) added the
  per-commit `:rf.view/causes` vector — this file now asserts a first connected
  commit is caused by `:mount`. The record still invents NO :parent-render-key
  (deferred — no capture machinery) and claims NO singular top-level :frame-id
  (frame attribution is PER-OBSERVATION). The full per-cause cause-vector proof
  lives in `reactive_commit_causes_cljs_test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private fid :rf/default)

(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- render!
  "Simulate one render pass over `sites` (`[[sid query] …]`) under the ambient
  frame, returning the immutable capture — ownership-free, never committed."
  [cell sites]
  (rf/with-frame fid
    (reactive/with-capture
      cell
      (fn [] (mapv (fn [[sid q]] (reactive/sub-read sid q)) sites)))))

(defn- render+commit! [cell sites]
  (let [[_ cap] (rf/with-frame fid
                  (reactive/with-capture
                    cell
                    (fn [] (mapv (fn [[sid q]] (reactive/sub-read sid q)) sites))))]
    (reactive/commit! cell cap))
  cell)

;; ===========================================================================
;; A connected commit mints the committed-instance record (Ruling 1 shape)
;; ===========================================================================

(deftest connected-commit-mints-the-committed-instance-record
  (rf/reg-sub :cr/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (is (nil? (reactive/commit-record cell))
        "a fresh, never-committed cell has no committed-instance record")
    (render+commit! cell [[[:cr/site 0] [:cr/a]]])
    (let [record (reactive/commit-record cell)]
      (is (map? record) "a connected commit publishes a per-commit record")
      (is (integer? (:render-key record)) ":render-key is an integer")
      (is (= ::v (:view-id record)) ":view-id is the cell's authoring identity")
      (is (= 0 (:generation record)) ":generation is the committed body generation")
      (is (= :connected (:connection record))
          ":connection is the certified :connected state at a connected commit")
      (is (vector? (:observations record)) ":observations is a vector")
      (is (= (reactive/render-key cell) (:render-key record))
          "the render-key reader mirrors the record's :render-key"))
    (testing "slice-a scope fences — the record still invents no deferred fields"
      (let [record (reactive/commit-record cell)]
        (is (not (contains? record :parent-render-key))
            "no :parent-render-key is invented (deferred — no capture machinery)")
        (is (not (contains? record :frame-id))
            "no singular top-level :frame-id (attribution is per-observation)")))
    (testing "slice-b adds the per-commit :rf.view/causes vector of cause records (Ruling 2)"
      (let [record (reactive/commit-record cell)]
        (is (contains? record :rf.view/causes)
            "the causes vector now ships on the record")
        (is (vector? (:rf.view/causes record)) ":rf.view/causes is a vector")
        (is (= [{:cause :mount}] (:rf.view/causes record))
            "a first connected commit is caused by :mount (a bare cause record)")))))

;; ===========================================================================
;; render-key is monotonic, minted fresh PER COMMIT
;; ===========================================================================

(deftest render-key-increments-monotonically-per-commit
  (rf/reg-sub :cr/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)
        keys (mapv (fn [_]
                     (render+commit! cell [[[:cr/site 0] [:cr/a]]])
                     (reactive/render-key cell))
                   (range 3))]
    (is (every? integer? keys) "every connected commit mints an integer render-key")
    (is (apply < keys)
        (str "render-key increments fresh per connected commit — " (pr-str keys)))))

;; ===========================================================================
;; StrictMode replay idempotence (rf2-8ds0v, PR #6567) — re-committing the EXACT
;; SAME capture mints NO second record, does not advance render-key, and does not
;; overwrite the genuine :mount record with a spurious :foreign-or-react one.
;; ===========================================================================

(deftest re-committing-the-same-capture-is-idempotent-under-strictmode-replay
  (rf/reg-sub :cr/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell    (reactive/make-cell ::v)
        ;; ONE render → ONE immutable capture. React 19 StrictMode re-invokes the
        ;; reconcile layout effect (setup→cleanup→setup) with THIS exact capture
        ;; object; the substrate must treat the re-commit as idempotent.
        [_ cap] (render! cell [[[:cr/site 0] [:cr/a]]])]
    ;; First commit of the fresh capture mints the genuine committed instance.
    (reactive/commit! cell cap)
    (let [rec1 (reactive/commit-record cell)
          rk1  (reactive/render-key cell)]
      (is (= [{:cause :mount}] (:rf.view/causes rec1))
          "the first connected commit of this capture is caused by :mount")
      (is (integer? rk1) "it minted an integer render-key")
      ;; Drive the StrictMode effect double-invoke at the substrate seam: the
      ;; layout-effect CLEANUP disconnects the cell, then the effect re-runs with
      ;; the IDENTICAL capture (React did not re-render between cleanup and setup).
      (reactive/disconnect! cell)
      (reactive/commit! cell cap)
      (let [rec2 (reactive/commit-record cell)
            rk2  (reactive/render-key cell)]
        (is (identical? rec1 rec2)
            "the replayed re-commit published NO new record — the genuine first
             record stands (not overwritten)")
        (is (= [{:cause :mount}] (:rf.view/causes rec2))
            "the :mount cause is NOT overwritten by a spurious :foreign-or-react
             replay record")
        (is (= rk1 rk2)
            "render-key does NOT advance twice for ONE rendered commit")))))

(deftest a-fresh-render-after-a-commit-still-mints-a-new-record
  ;; The idempotence guard keys on capture OBJECT identity, so a genuine
  ;; re-render (a DISTINCT capture) is never mistaken for a replay — it mints a
  ;; new record with a strictly greater render-key.
  (rf/reg-sub :cr/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell    (reactive/make-cell ::v)
        [_ cap1] (render! cell [[[:cr/site 0] [:cr/a]]])]
    (reactive/commit! cell cap1)
    (let [rk1 (reactive/render-key cell)
          [_ cap2] (render! cell [[[:cr/site 0] [:cr/a]]])]
      (is (not (identical? cap1 cap2)) "each render produces a distinct capture")
      (reactive/commit! cell cap2)
      (is (> (reactive/render-key cell) rk1)
          "a distinct capture is a distinct committed render — render-key advances"))))

;; ===========================================================================
;; Observations carry PER-OBSERVATION frame attribution (Ruling-1 amendment)
;; ===========================================================================

(deftest observations-carry-per-observation-frame-id
  (rf/reg-sub :cr/a (fn [db _] (:a db)))
  (rf/reg-sub :cr/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 2})
  (let [cell (reactive/make-cell ::v)]
    ;; First commit builds the nodes; the SECOND render probes them live, so the
    ;; second record's observations carry the observed node identity + version.
    (render+commit! cell [[[:cr/site 0] [:cr/a]] [[:cr/site 1] [:cr/b]]])
    (render+commit! cell [[[:cr/site 0] [:cr/a]] [[:cr/site 1] [:cr/b]]])
    (let [obs (:observations (reactive/commit-record cell))]
      (is (= 2 (count obs)) "one observation per rendered site, in render order")
      (is (= [[:cr/a] [:cr/b]] (mapv :query obs))
          "observations preserve compiler render order + carry each site's query")
      (is (every? (fn [o] (= :subscription (:kind o))) obs)
          "each subscription read is a :subscription observation")
      (is (every? (fn [o] (= fid (:frame-id o))) obs)
          "each observation carries its OWN target's frame-id — per-observation
           attribution, not a single fabricated frame fact")
      (is (every? :owned? obs)
          "an owned subscription node handle reports :owned? true")
      (is (every? (fn [o] (integer? (:target-id o))) obs)
          "the observed live node identity is captured (:target-id)")
      (is (every? (fn [o] (integer? (:version o))) obs)
          "the observed live node version is captured (:version)")
      (is (= #{fid} (into #{} (map :frame-id) obs))
          "the frames a cell observes are a SET derived from observations —
           there is no singular :frame-id on the record"))))

;; ===========================================================================
;; Speculative renders fabricate no instance (I-1/I-2)
;; ===========================================================================

(deftest speculative-renders-publish-no-record
  (rf/reg-sub :cr/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (dotimes [_ 100]
      (render! cell [[[:cr/site 0] [:cr/a]]]))
    (is (nil? (reactive/commit-record cell))
        "100 renders that never commit fabricate NO committed-instance record")))

;; ===========================================================================
;; The render-key source is MODULE-GLOBAL — shared across cells
;; ===========================================================================

(deftest render-key-is-a-module-global-source-across-cells
  (rf/reg-sub :cr/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell-a (reactive/make-cell ::a)
        cell-b (reactive/make-cell ::b)]
    (render+commit! cell-a [[[:cr/site 0] [:cr/a]]])
    (render+commit! cell-b [[[:cr/site 0] [:cr/a]]])
    (is (< (reactive/render-key cell-a) (reactive/render-key cell-b))
        "two cells draw increasing render-keys from ONE module-global source")))
