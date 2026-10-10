;;;; tests/runtime/preload_sentinel_test.clj — the preload installs the
;;;; `js/globalThis.__re_frame2_pair_runtime` marker at load time. The MCP
;;;; server's `discover-app` probes it and refuses with
;;;; `:reason :runtime-not-preloaded` when absent, so losing it breaks every
;;;; session in the same misleading way.
;;;;
;;;; Run: bb tests/runtime/preload_sentinel_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns preload-sentinel-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest sentinel-defonce-installs-the-global-marker
  (let [sentinel (some #(when (and (seq? %) (= 'defonce (first %))
                                   (rt/mentions? "__re_frame2_pair_runtime" %))
                          %)
                       rt/all-forms)]
    (is (rt/mentions? 'js/globalThis sentinel)
        "a top-level defonce must install the \"__re_frame2_pair_runtime\" marker on js/globalThis")
    (is (rt/mentions? 'session-id sentinel)
        "the marker must carry session-id, the in-browser handle")))

(let [{:keys [fail error]} (run-tests 'preload-sentinel-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
