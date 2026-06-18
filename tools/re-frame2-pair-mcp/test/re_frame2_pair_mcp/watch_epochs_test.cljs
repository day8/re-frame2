(ns re-frame2-pair-mcp.watch-epochs-test
  "Unit tests for the `watch-epochs` MCP tool — specifically the
  empty-result advisory added in rf2-fb4hn.

  watch-epochs returns matches that landed AFTER a :since-id AND
  satisfy an optional :pred. When matches is empty the operator wants
  to know which gate filtered the result:

    - The :since-id is at (or past) the head — calmly \"nothing new\".
    - Events landed since the id, but the :pred excluded them all —
      point at the predicate.
    - Genuinely empty history — no advisory.

  Pre-rf2-fb4hn these three cases all looked identical (empty matches +
  no further detail), feeding a misread that the listener wasn't
  capturing events."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]))

(defn- with-substr-eval!
  [script body-fn]
  (let [orig nrepl/cljs-eval-value
        match (fn [form-str]
                (some (fn [[k v]]
                        (when (or (= k :default)
                                  (and (string? k) (re-find (re-pattern k) form-str)))
                          v))
                      script))
        stub (fn
               ([_conn _build-id form-str]
                (js/Promise.resolve (match form-str)))
               ([_conn _build-id form-str _opts]
                (js/Promise.resolve (match form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

;; ---------------------------------------------------------------------------
;; Empty matches + empty history → no advisory.
;; ---------------------------------------------------------------------------

(deftest empty-history-no-advisory
  (testing "watch-epochs with truly empty per-frame ring → no advisory"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:matches        []
                                                  :id-aged-out?   false
                                                  :requested-id   nil
                                                  :head-id        nil
                                                  :next-id        nil
                                                  :history-count  0
                                                  :since-count    0
                                                  :remaining      0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (we/watch-epochs-tool nil (tu/args->js {}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (= 0 (:count edn)))
                               (is (not (contains? edn :advisory)))
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Empty matches + history non-empty + since-count = 0 → :no-events-since-id.
;; ---------------------------------------------------------------------------

(deftest history-non-empty-but-nothing-since-id
  (testing "watch-epochs at head of ring → :no-events-since-id advisory"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:matches        []
                                                  :id-aged-out?   false
                                                  :requested-id   :epoch-9
                                                  :head-id        :epoch-9
                                                  :next-id        nil
                                                  :history-count  9
                                                  :since-count    0
                                                  :remaining      0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (we/watch-epochs-tool nil (tu/args->js {:since-id ":epoch-9"
                                                            :frame "step-deck"}))
                    (.then (fn [result]
                             (let [edn      (tu/extract-edn result)
                                   advisory (:advisory edn)]
                               (is (some? advisory))
                               (is (= :no-events-since-id (:reason advisory)))
                               (is (= 9 (:epochs-in-history advisory)))
                               (is (= :step-deck (:frame advisory)))
                               (is (re-find #"none have landed since" (:hint advisory)))
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Empty matches + since-count > 0 → :pred-excludes-history.
;; ---------------------------------------------------------------------------

(deftest pred-filters-all-events-since-id
  (testing "watch-epochs with :pred excluding every event → :pred-excludes-history"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:matches        []
                                                  :id-aged-out?   false
                                                  :requested-id   :epoch-3
                                                  :head-id        :epoch-9
                                                  :next-id        nil
                                                  :history-count  9
                                                  :since-count    6
                                                  :remaining      0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (we/watch-epochs-tool
                      nil
                      (tu/args->js {:since-id ":epoch-3"
                                    :pred #js {:event-id ":no/match"}}))
                    (.then (fn [result]
                             (let [edn      (tu/extract-edn result)
                                   advisory (:advisory edn)]
                               (is (some? advisory))
                               (is (= :pred-excludes-history (:reason advisory)))
                               (is (= 9 (:epochs-in-history advisory)))
                               (is (= 6 (:epochs-since-id advisory)))
                               (is (re-find #":pred filter excluded"
                                            (:hint advisory)))
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Non-empty matches → no advisory.
;; ---------------------------------------------------------------------------

(deftest non-empty-matches-no-advisory
  (testing "watch-epochs with matches → no advisory"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:matches        [{:epoch-id :e1}]
                                                  :id-aged-out?   false
                                                  :requested-id   nil
                                                  :head-id        :e1
                                                  :next-id        nil
                                                  :history-count  5
                                                  :since-count    5
                                                  :remaining      0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (we/watch-epochs-tool nil (tu/args->js {}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (= 1 (:count edn)))
                               (is (not (contains? edn :advisory)))
                               (done))))))))))))
