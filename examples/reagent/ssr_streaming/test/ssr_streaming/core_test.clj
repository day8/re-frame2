(ns ssr-streaming.core-test
  "Headless test for ssr-streaming.core — exercises the server stream
  (shell render → per-card resolved chunks → final payload). Kept out of
  the example source so the example a learner reads is pure demonstrative
  code (the same test-free split realworld / nine_states / boot use).

  JVM-only: the streaming server path runs under Clojure. Driven by
  re-frame.examples-test."
  (:require [clojure.string]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [ssr-streaming.core :as core]))

(defn streaming-tests []
  (rf/init! ssr/adapter)
  (let [result (core/handle-request {:uri "/dashboard"})]
    ;; Shell carries the static header content + four template fallbacks.
    (assert (clojure.string/includes? (:shell result) "<h1>Dashboard</h1>"))
    (assert (clojure.string/includes? (:shell result) "data-rf2-suspense-fallback=\"1\""))
    (assert (= 4 (count (:resolved-chunks result)))
            "four boundaries → four resolved chunks")
    ;; Three cards rendered successfully; one (flaky) ships the
    ;; fallback HTML with data-rf2-suspense-failed.
    (let [failed-chunks (filter :failed? (:resolved-chunks result))]
      (assert (= 1 (count failed-chunks))
              "one boundary (flaky) ships the failed template")
      (assert (clojure.string/includes? (:template (first failed-chunks))
                                         "data-rf2-suspense-failed=\"1\"")))
    ;; Successful cards carry the rendered card body.
    (let [ok-chunks (remove :failed? (:resolved-chunks result))]
      (assert (= 3 (count ok-chunks)))
      (doseq [c ok-chunks]
        (assert (clojure.string/includes? (:template c)
                                           "data-rf2-suspense-resolved=\"1\""))))
    ;; Final payload carries the canonical :rf/* keys.
    (assert (= 1 (:rf/version (:final-payload result))))
    (assert (some? (:rf/render-hash (:final-payload result))))
    (assert (= 3 (count (:cards (:rf/app-db (:final-payload result)))))
            "three cards' state in the final payload (revenue, signups, latency); the flaky card has no app-db slice because it threw before its data fetched")
    :ok))
