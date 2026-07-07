(ns re-frame2-pair-mcp.operating-frame-test
  "Unit tests for `get-operating-frame` / `reset-operating-frame`'s
  blank-runtime-result handling.

  `set-operating-frame-tool` already routes a non-map / `:ok? false`
  runtime answer through `wire/err-text`; `get-operating-frame-tool` and
  `reset-operating-frame-tool` did not — a nil / non-map answer from a
  degraded runtime (the eval came back blank) silently rode back as
  `wire/ok-text` despite carrying `:ok? false` (rf2-acckgr). These tests
  pin the fix: BOTH siblings now match `set-operating-frame-tool`'s
  guard."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.operating-frame :as op-frame]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- stub-eval!
  "Install a `cljs-eval-value` stub. Answers the preload probe with
  `true` (so `ensure-runtime!` short-circuits) and every other
  (non-prelude) eval with `canned` — the shape `get-form` /
  `reset-form` resolve to."
  [canned]
  (let [respond (fn [form]
                  (if (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
                    (js/Promise.resolve true)
                    (js/Promise.resolve canned)))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(defn- restore-eval! [] (set! nrepl/cljs-eval-value pristine-eval))

(deftest get-operating-frame-blank-runtime-result-is-isError
  (async done
    (stub-eval! nil)
    (-> (op-frame/get-operating-frame-tool (fresh-conn) (tu/args->js {}))
        (.then (fn [r]
                 (is (tu/error? r)
                     "a blank/non-map get-operating-frame result MUST be isError: true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :unexpected-shape (:reason edn))))
                 (restore-eval!)
                 (done))))))

(deftest get-operating-frame-healthy-map-still-succeeds
  ;; Negative guard: a genuine `frames-list` map keeps riding as ok-text.
  (async done
    (stub-eval! {:ok? true :frames [:rf/default] :selected nil :operating :rf/default})
    (-> (op-frame/get-operating-frame-tool (fresh-conn) (tu/args->js {}))
        (.then (fn [r]
                 (is (not (tu/error? r)) "a healthy frames-list map is not an error")
                 (is (true? (:ok? (tu/extract-edn r))))
                 (restore-eval!)
                 (done))))))

(deftest reset-operating-frame-blank-runtime-result-is-isError
  (async done
    (stub-eval! nil)
    (-> (op-frame/reset-operating-frame-tool (fresh-conn) (tu/args->js {}))
        (.then (fn [r]
                 (is (tu/error? r)
                     "a blank/non-map reset-operating-frame result MUST be isError: true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :unexpected-shape (:reason edn))))
                 (restore-eval!)
                 (done))))))

(deftest reset-operating-frame-healthy-map-still-succeeds
  (async done
    (stub-eval! {:ok? true :frames [:rf/default] :selected nil :operating :rf/default})
    (-> (op-frame/reset-operating-frame-tool (fresh-conn) (tu/args->js {}))
        (.then (fn [r]
                 (is (not (tu/error? r)) "a healthy frames-list map is not an error")
                 (is (true? (:ok? (tu/extract-edn r))))
                 (restore-eval!)
                 (done))))))
