(ns re-frame2-pair-mcp.trace-window-test
  "Unit tests for the `trace-window` MCP tool: the ring read, the
  empty-result advisory and the cursor paths, driven through the real tool.

  The advisory tells \"genuinely empty history\" apart from \"the window
  excludes the history\", which a bare `:count 0` cannot. Frame
  resolution and cursor frame ownership live in `epoch_frame_test`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.trace-window :as tw]))

;; ---------------------------------------------------------------------------
;; Stub harness — `cljs-eval-value` is scripted by form-keyword
;; substring: a sequence of [substring response] pairs picks the first
;; pair whose substring appears in the eval form (probe forms contain
;; `__re_frame2_pair_runtime`; trace-window's slice form contains
;; `epoch-history`). A `:default` sentinel is the fall-through.
;; ---------------------------------------------------------------------------

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

(defn- trace!
  "Run trace-window on `args` against a runtime whose slice answers `answer`."
  [args answer]
  (with-substr-eval! [["__re_frame2_pair_runtime" true] [:default answer]]
    #(tw/trace-window-tool nil (tu/args->js args))))

(deftest non-empty-ring-returns-epochs
  (async done
    (-> (trace! {:ms 60000}
                {:epochs        [{:epoch-id :e1 :committed-at 100}
                                 {:epoch-id :e2 :committed-at 200}
                                 {:epoch-id :e3 :committed-at 300}]
                 :id-aged-out?  false
                 :head-id       :e3
                 :history-count 20
                 :remaining     0})
        (.then (fn [result]
                 (let [edn (tu/extract-edn result)]
                   (is (= 3 (:count edn)))
                   (is (= [:e1 :e2 :e3] (mapv :epoch-id (tu/dedup-expand (:epochs edn))))
                       "the ring's epochs ride back in order")
                   (is (not (contains? edn :advisory))
                       "no advisory when epochs land in the window, whatever the history size")
                   (done)))))))

(deftest empty-history-no-advisory
  (async done
    (-> (trace! {:ms 1000} {:epochs [] :id-aged-out? false :history-count 0 :remaining 0})
        (.then (fn [result]
                 (let [edn (tu/extract-edn result)]
                   (is (= 0 (:count edn)))
                   (is (not (contains? edn :advisory)) "a genuinely empty history needs no advisory")
                   (done)))))))

(deftest empty-window-non-empty-history-surfaces-advisory
  (async done
    (-> (trace! {:ms 1000 :frame "step-deck"}
                {:epochs [] :id-aged-out? false :head-id :epoch-9 :history-count 9 :remaining 0})
        (.then (fn [result]
                 (is (= {:reason            :window-excludes-history
                         :frame             :step-deck
                         :epochs-in-history 9
                         :window-ms         1000}
                        (dissoc (:advisory (tu/extract-edn result)) :hint)))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Cursor paths through the REAL tool. cursor_pagination_test checks the
;; slice and mint logic against a hand-reimplemented copy; these keep the
;; real envelope from diverging from it.
;; ---------------------------------------------------------------------------

(deftest malformed-cursor-returns-cursor-stale
  (async done
    ;; No eval stub: the malformed branch returns before any runtime round-trip.
    (-> (tw/trace-window-tool nil (tu/args->js {:cursor "AAAA"}))
        (.then (fn [result]
                 (is (tu/error? result))
                 (is (= {:reason :rf.mcp/cursor-stale :tool "trace-window"}
                        (select-keys (tu/extract-edn result) [:reason :tool])))
                 (done))))))

(deftest aged-out-ring-returns-cursor-stale
  (async done
    (-> (trace! {:ms 1000}
                {:epochs [] :id-aged-out? true :requested-id :epoch-7 :head-id :epoch-57
                 :history-count 50 :remaining 0})
        (.then (fn [result]
                 (is (tu/error? result))
                 (is (= {:reason       :rf.mcp/cursor-stale
                         :tool         "trace-window"
                         :requested-id :epoch-7
                         :head-id      :epoch-57}
                        (select-keys (tu/extract-edn result)
                                     [:reason :tool :requested-id :head-id])))
                 (done))))))

(deftest next-cursor-mint-encodes-sticky-window-and-frame
  (async done
    (-> (trace! {:ms 5000 :frame ":rf/default"}
                {:epochs        [{:epoch-id :e1 :committed-at 100}
                                 {:epoch-id :e2 :committed-at 200}]
                 :id-aged-out?  false
                 :head-id       :e3
                 :next-id       :e2
                 :history-count 3
                 :remaining     1})
        (.then (fn [result]
                 (let [edn     (tu/extract-edn result)
                       decoded (cursor/decode-cursor (:next-cursor edn))]
                   (is (true? (:has-more? edn)))
                   (is (= {:after-id :e2 :ms 5000 :frame :rf/default}
                          (select-keys decoded [:after-id :ms :frame]))
                       "page 2 resumes after the last epoch, in the same window and frame")
                   (is (number? (:until-ms decoded))
                       "the first-call clock is pinned so page 2 sees the same window")
                   (done)))))))
