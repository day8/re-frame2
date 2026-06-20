(ns re-frame2-pair-mcp.get-stream-controls-test
  "Unit tests for the `get-stream-controls` diagnostic tool.

  The tool is a pure read over the server-side `resource-controls`
  atoms — no nREPL round-trip — so these tests drive the controller
  into known states (acquire slots, drain the token bucket, push the
  abuse window) and assert the reported diagnostic shape directly off
  the wire envelope."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [re-frame2-pair-mcp.tools.resource-controls :as resource]
            [re-frame2-pair-mcp.tools.get-stream-controls :as gsc]))

(use-fixtures :each
  {:before (fn [] (resource/reset-for-tests!))
   :after  (fn [] (resource/reset-for-tests!))})

(defn- result-edn
  "Pull the EDN payload off a tool result envelope."
  [^js result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))
        text    (when item (j/get item :text))]
    (some-> text edn/read-string)))

(defn- call! [f]
  (-> (gsc/get-stream-controls-tool nil #js {})
      (.then (fn [r] (f (result-edn r))))))

(deftest reports-defaults-on-fresh-session
  (async done
    (-> (call!
          (fn [edn]
            (is (true? (:ok? edn)))
            (testing "effective config = documented defaults"
              (is (= 10    (get-in edn [:config :max-concurrent-streams])))
              (is (= 100   (get-in edn [:config :max-events-per-sec])))
              (is (= 50    (get-in edn [:config :abuse-overflow-threshold])))
              (is (= 10000 (get-in edn [:config :abuse-window-ms]))))
            (testing "no streams acquired"
              (is (= 0  (get-in edn [:concurrent-streams :active])))
              (is (= 10 (get-in edn [:concurrent-streams :limit])))
              (is (false? (get-in edn [:concurrent-streams :at-capacity?]))))
            (testing "rate bucket lazily uninitialised, reports full capacity"
              (is (= 100 (get-in edn [:rate-limit :capacity])))
              (is (= 100 (get-in edn [:rate-limit :tokens])))
              (is (false? (get-in edn [:rate-limit :initialized?])))
              (is (false? (get-in edn [:rate-limit :throttling?]))))
            (testing "abuse window empty"
              (is (= 0  (get-in edn [:abuse-window :count])))
              (is (= 50 (get-in edn [:abuse-window :threshold])))
              (is (false? (get-in edn [:abuse-window :tripped?]))))
            (testing "carries the cross-check hint"
              (is (string? (:cross-check edn))))))
        (.then done))))

(deftest reflects-active-slots-and-at-capacity
  (async done
    ;; Tighten the cap to 2 then acquire both slots.
    (resource/set-config! {:max-concurrent-streams 2})
    (resource/acquire-stream!)
    (resource/acquire-stream!)
    (-> (call!
          (fn [edn]
            (is (= 2 (get-in edn [:concurrent-streams :active])))
            (is (= 2 (get-in edn [:concurrent-streams :limit])))
            (is (true? (get-in edn [:concurrent-streams :at-capacity?])))))
        (.then done))))

(deftest reflects-rate-bucket-throttling
  (async done
    ;; A 1-event/sec bucket: one check consumes the only token, the
    ;; bucket sits below 1.0 ⇒ throttling.
    (resource/set-config! {:max-events-per-sec 1})
    (resource/check-rate!)
    (-> (call!
          (fn [edn]
            (is (true? (get-in edn [:rate-limit :initialized?])))
            (is (true? (get-in edn [:rate-limit :throttling?]))
                "bucket drained below one token ⇒ throttling? true")))
        (.then done))))

(deftest reflects-abuse-window-trip
  (async done
    ;; Threshold 1: two overflows trip the window (count EXCEEDS threshold).
    (resource/set-config! {:abuse-overflow-threshold 1 :abuse-window-ms 60000})
    (resource/record-overflow!)
    (resource/record-overflow!)
    (-> (call!
          (fn [edn]
            (is (= 2 (get-in edn [:abuse-window :count])))
            (is (= 1 (get-in edn [:abuse-window :threshold])))
            (is (true? (get-in edn [:abuse-window :tripped?]))
                "count (2) > threshold (1) ⇒ tripped? true")))
        (.then done))))
