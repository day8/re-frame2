(ns re-frame2-pair-mcp.watch-epochs-test
  "Unit tests for the `watch-epochs` MCP tool: the empty-result advisory
  and the cursor-stale paths, driven through the real tool.

  An empty `:matches` has three causes the advisory tells apart: a
  genuinely empty history (no advisory), a `:since-id` at the head
  (`:no-events-since-id`, pinned in `epoch_frame_test`), and a `:pred`
  that excluded everything since the id (`:pred-excludes-history`)."
  (:require [cljs.test :refer-macros [deftest is async]]
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

(defn- watch!
  "Run watch-epochs on `args` against a runtime whose poll answers `answer`."
  [args answer]
  (with-substr-eval! [["__re_frame2_pair_runtime" true] [:default answer]]
    #(we/watch-epochs-tool nil (tu/args->js args))))

(deftest empty-history-no-advisory
  (async done
    (-> (watch! {} {:matches [] :id-aged-out? false :history-count 0 :since-count 0 :remaining 0})
        (.then (fn [result]
                 (let [edn (tu/extract-edn result)]
                   (is (= 0 (:count edn)))
                   (is (not (contains? edn :advisory)))
                   (done)))))))

(deftest pred-filters-all-events-since-id
  (async done
    (-> (watch! {:since-id ":epoch-3" :pred #js {:event-id ":no/match"}}
                {:matches [] :id-aged-out? false :requested-id :epoch-3 :head-id :epoch-9
                 :history-count 9 :since-count 6 :remaining 0})
        (.then (fn [result]
                 (is (= {:reason :pred-excludes-history :epochs-in-history 9 :epochs-since-id 6}
                        (select-keys (:advisory (tu/extract-edn result))
                                     [:reason :epochs-in-history :epochs-since-id])))
                 (done))))))

(deftest non-empty-matches-no-advisory
  (async done
    (-> (watch! {} {:matches [{:epoch-id :e1}] :id-aged-out? false :head-id :e1
                    :history-count 5 :since-count 5 :remaining 0})
        (.then (fn [result]
                 (let [edn (tu/extract-edn result)]
                   (is (= 1 (:count edn)))
                   (is (not (contains? edn :advisory)))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Cursor-stale paths through the REAL tool. cursor_pagination_test checks
;; the slice logic against a hand-reimplemented copy; these keep the real
;; envelope and branch condition from diverging from it.
;; ---------------------------------------------------------------------------

(deftest malformed-cursor-returns-cursor-stale
  (async done
    ;; No eval stub: the malformed branch returns before any runtime round-trip.
    (-> (we/watch-epochs-tool nil (tu/args->js {:cursor "not-a-valid-cursor!!!"}))
        (.then (fn [result]
                 (is (tu/error? result))
                 (is (= {:reason :rf.mcp/cursor-stale :tool "watch-epochs"}
                        (select-keys (tu/extract-edn result) [:reason :tool])))
                 (done))))))

(deftest aged-out-ring-returns-cursor-stale
  (async done
    (-> (watch! {:since-id ":epoch-99"}
                {:matches [] :id-aged-out? true :requested-id :epoch-99 :head-id :epoch-149
                 :history-count 50 :since-count 0 :remaining 0})
        (.then (fn [result]
                 (is (tu/error? result))
                 (is (= {:reason       :rf.mcp/cursor-stale
                         :tool         "watch-epochs"
                         :requested-id :epoch-99
                         :head-id      :epoch-149}
                        (select-keys (tu/extract-edn result)
                                     [:reason :tool :requested-id :head-id]))
                     "the dead id and the current head ride back for a rewind")
                 (done))))))
