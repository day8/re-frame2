(ns day8.re-frame2-causa.panels.shared.focus-resolver-cljs-test
  "Pure-data tests for the shared focus-resolver (rf2-o9suo).

  ## Why the `.cljc` + `_cljs_test` naming

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    - `resolve-focus-status` — classifies the focus + history pair
      into `:no-focus` / `:focused` / `:epoch-evicted`, honouring the
      rf2-h0120 head-fallback (nil focus + non-empty history →
      `:focused`).
    - `find-epoch-record` — looks up the matching `:rf/epoch-record`
      or returns the head record under the same head-fallback
      contract."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.shared.focus-resolver :as focus]))

;; ---- fixture builders ---------------------------------------------------

(defn- epoch-record
  ([trace-events]
   (epoch-record 1 trace-events))
  ([epoch-id trace-events]
   {:epoch-id     epoch-id
    :trace-events (vec trace-events)}))

;; ---- resolve-focus-status ----------------------------------------------

(deftest resolve-focus-status-no-focus
  (testing "focus nil AND history empty → cold start, :no-focus"
    (is (= :no-focus (focus/resolve-focus-status nil [])))
    (is (= :no-focus (focus/resolve-focus-status nil nil)))))

(deftest resolve-focus-status-head-fallback
  (testing "rf2-h0120 — focus nil but history non-empty → head-fallback
            (resolves to :focused; the find-epoch-record lookup returns
            the most-recent record). This is the natural debugging UX —
            show the latest unless the operator explicitly picks an
            earlier row."
    (let [hist [(epoch-record 1 []) (epoch-record 2 []) (epoch-record 3 [])]]
      (is (= :focused (focus/resolve-focus-status nil hist))))
    (testing "single-record history also resolves to :focused"
      (is (= :focused (focus/resolve-focus-status nil [(epoch-record 1 [])]))))))

(deftest resolve-focus-status-focused-match
  (let [hist [(epoch-record 1 []) (epoch-record 2 []) (epoch-record 3 [])]]
    (is (= :focused (focus/resolve-focus-status 1 hist)))
    (is (= :focused (focus/resolve-focus-status 2 hist)))
    (is (= :focused (focus/resolve-focus-status 3 hist)))))

(deftest resolve-focus-status-epoch-evicted
  (testing "focus has :epoch-id but history doesn't carry it → evicted"
    (let [hist [(epoch-record 5 []) (epoch-record 6 []) (epoch-record 7 [])]]
      (is (= :epoch-evicted (focus/resolve-focus-status 1 hist)))
      (is (= :epoch-evicted (focus/resolve-focus-status 99 hist))))))

(deftest resolve-focus-status-empty-history-with-focus-id
  (testing "focus pins an :epoch-id but the history is empty → evicted"
    (is (= :epoch-evicted (focus/resolve-focus-status 1 []))))
  (testing "focus pins an :epoch-id but history is nil → evicted"
    (is (= :epoch-evicted (focus/resolve-focus-status 1 nil)))))

;; ---- find-epoch-record -------------------------------------------------

(deftest find-epoch-record-returns-match
  (let [hist [(epoch-record 5 [{:id 100}])
              (epoch-record 6 [{:id 101}])]]
    (is (= 5 (:epoch-id (focus/find-epoch-record 5 hist))))
    (is (= 6 (:epoch-id (focus/find-epoch-record 6 hist))))
    (is (nil? (focus/find-epoch-record 99 hist)))))

(deftest find-epoch-record-head-fallback
  (testing "rf2-h0120 — focus nil + history non-empty returns the HEAD
            (most-recent) record. epoch-history is oldest-first per
            re-frame.epoch/epoch-history, so the head is the last
            element."
    (let [hist [(epoch-record 5 [{:id 100}])
                (epoch-record 6 [{:id 101}])
                (epoch-record 7 [])]]
      (is (= 7 (:epoch-id (focus/find-epoch-record nil hist))))))
  (testing "single-record history's head is that single record"
    (let [hist [(epoch-record 42 [{:id 1}])]]
      (is (= 42 (:epoch-id (focus/find-epoch-record nil hist))))))
  (testing "focus nil AND history empty/nil returns nil"
    (is (nil? (focus/find-epoch-record nil [])))
    (is (nil? (focus/find-epoch-record nil nil)))))

(deftest find-epoch-record-list-fallback
  (testing "find-epoch-record handles non-vector (seq) history via `last`
            — production sub joins on the framework's vector-backed slot,
            but a caller handing in a seq must not return nil silently."
    (let [hist (list (epoch-record 5 [])
                     (epoch-record 6 [])
                     (epoch-record 7 []))]
      (is (= 7 (:epoch-id (focus/find-epoch-record nil hist)))
          "head-fallback over a seq uses `last`")
      (is (= 5 (:epoch-id (focus/find-epoch-record 5 hist)))
          "lookup by id traverses seqs the same way"))))

;; ---- composite — exercise the sub call-site shape ---------------------

(deftest resolver-end-to-end-head-fallback
  (testing "rf2-h0120 + rf2-o9suo — the L4 sub call-site shape: when
            :rf.causa/focus carries no :epoch-id but :rf.causa/epoch-
            history has records, resolve-focus-status returns :focused
            AND find-epoch-record returns the head. Panels relying on
            this contract (Issues, App-db downstream, Reactive) share
            ONE source of truth."
    (let [hist           [(epoch-record 5 [])
                          (epoch-record 6 [{:id 1 :op-type :error
                                            :operation :rf.error/schema-violation}])]
          focus-epoch-id nil
          focus-status   (focus/resolve-focus-status focus-epoch-id hist)
          record         (focus/find-epoch-record   focus-epoch-id hist)]
      (is (= :focused focus-status))
      (is (= 6 (:epoch-id record)) "head record is the most-recent epoch")
      (is (= 1 (count (:trace-events record)))))))
