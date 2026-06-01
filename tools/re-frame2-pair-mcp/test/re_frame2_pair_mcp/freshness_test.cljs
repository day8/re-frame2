(ns re-frame2-pair-mcp.freshness-test
  "Unit tests for the freshness / liveness token (rf2-ertqw).

  The token's job is to make 'the runtime I'm reading is stale /
  disconnected / serving a STALE BUILD' obvious up front. These tests
  pin the cross-check verdict logic (`liveness-verdict`), the
  assembly/merge of the browser + JVM halves (`assemble`), and the
  graceful degradation when the JVM half can't be read.

  The load-bearing case is `:stale-build`: a build whose last flush is
  newer than the moment the browser code loaded — the rf2-lo28u killer."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.tools.freshness :as fresh]))

;; ---------------------------------------------------------------------------
;; liveness-verdict — the cross-check.
;; ---------------------------------------------------------------------------

(deftest verdict-unknown-when-jvm-half-absent
  (is (= :unknown
         (fresh/liveness-verdict {:jvm-read? false
                                  :runtime-loaded-at 1000
                                  :build-flushed-at 2000
                                  :runtime-count 1}))
      "No JVM half ⇒ :unknown regardless of the browser fields"))

(deftest verdict-no-runtime-when-zero-runtimes
  (is (= :no-runtime
         (fresh/liveness-verdict {:jvm-read? true
                                  :runtime-count 0
                                  :build-flushed-at 1000
                                  :runtime-loaded-at 2000}))
      "Zero connected runtimes ⇒ :no-runtime"))

(deftest verdict-no-runtime-when-heartbeat-stale
  (is (= :no-runtime
         (fresh/liveness-verdict {:jvm-read? true
                                  :runtime-count 1
                                  :heartbeat-age-ms 60000 ; > 30s threshold
                                  :build-flushed-at 1000
                                  :runtime-loaded-at 2000}))
      "A heartbeat older than the stale threshold ⇒ :no-runtime even with a runtime counted"))

(deftest verdict-stale-build-when-flush-newer-than-load
  (is (= :stale-build
         (fresh/liveness-verdict {:jvm-read? true
                                  :runtime-count 1
                                  :heartbeat-age-ms 500
                                  :runtime-loaded-at 1000
                                  :build-flushed-at 5000}))
      "Build flushed AFTER the runtime loaded ⇒ :stale-build (the rf2-lo28u killer)"))

(deftest verdict-fresh-when-load-newer-than-flush
  (is (= :fresh
         (fresh/liveness-verdict {:jvm-read? true
                                  :runtime-count 1
                                  :heartbeat-age-ms 500
                                  :runtime-loaded-at 5000
                                  :build-flushed-at 1000}))
      "Runtime loaded AFTER the last flush ⇒ :fresh (running the current build)"))

(deftest verdict-fresh-when-equal-timestamps
  ;; A flush exactly at load time is not stale — the running code IS that
  ;; build. Strict `>` matters here.
  (is (= :fresh
         (fresh/liveness-verdict {:jvm-read? true
                                  :runtime-count 1
                                  :heartbeat-age-ms 0
                                  :runtime-loaded-at 1000
                                  :build-flushed-at 1000}))
      "flush == load ⇒ :fresh (not stale)"))

(deftest verdict-no-runtime-beats-stale-build
  ;; Order: you can't be serving stale code if nothing's connected.
  (is (= :no-runtime
         (fresh/liveness-verdict {:jvm-read? true
                                  :runtime-count 0
                                  :runtime-loaded-at 1000
                                  :build-flushed-at 5000}))
      ":no-runtime wins over :stale-build"))

(deftest verdict-fresh-when-flush-missing
  ;; A build with no recorded flush timestamp (e.g. never recompiled
  ;; since boot) can't be proven stale — default to :fresh.
  (is (= :fresh
         (fresh/liveness-verdict {:jvm-read? true
                                  :runtime-count 1
                                  :heartbeat-age-ms 100
                                  :runtime-loaded-at 1000
                                  :build-flushed-at nil}))
      "Missing :build-flushed-at can't prove staleness ⇒ :fresh"))

;; ---------------------------------------------------------------------------
;; assemble — merge browser + JVM halves.
;; ---------------------------------------------------------------------------

(defn- with-jvm-half!
  "Stub `fresh/jvm-build-freshness` to resolve to `jvm-half` (a map or
  nil), run `body-fn`, restore. Lets us assert `assemble` without a
  live nREPL socket."
  [jvm-half body-fn]
  (let [orig fresh/jvm-build-freshness]
    (set! fresh/jvm-build-freshness (fn [_conn _bid] (js/Promise.resolve jvm-half)))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! fresh/jvm-build-freshness orig))))))

(def ^:private browser-half
  {:runtime-instance-id "uuid-abc"
   :runtime-loaded-at   1000
   :read-at             9999})

(deftest assemble-merges-fresh
  (async done
    (-> (with-jvm-half! {:compile-cycle 7
                         :build-flushed-at 500
                         :runtime-count 1
                         :heartbeat-age-ms 200}
          (fn [] (fresh/assemble nil :app browser-half)))
        (.then
          (fn [token]
            (is (= "uuid-abc" (:runtime-instance-id token)) "browser id carried through")
            (is (= 1000 (:runtime-loaded-at token)) "browser load time carried through")
            (is (= 7 (:compile-cycle token)) "JVM monotonic compile-cycle merged in")
            (is (= 500 (:build-flushed-at token)) "JVM flush timestamp merged in")
            (is (= :app (:build-id token)) "build-id stamped")
            (is (= :fresh (:liveness token)) "load (1000) > flush (500) ⇒ :fresh")
            (is (nil? (:hint token)) "no hint on a fresh verdict")
            (done))))))

(deftest assemble-flags-stale-build
  (async done
    (-> (with-jvm-half! {:compile-cycle 12
                         :build-flushed-at 5000 ; recompiled AFTER load (1000)
                         :runtime-count 1
                         :heartbeat-age-ms 100}
          (fn [] (fresh/assemble nil :app browser-half)))
        (.then
          (fn [token]
            (is (= :stale-build (:liveness token))
                "flush (5000) > load (1000) ⇒ :stale-build")
            (is (true? (fresh/stale-build? token)) "stale-build? sugar agrees")
            (is (re-find #"STALE BUILD" (:hint token))
                "hint names the stale-build alarm")
            (is (re-find #"4000ms" (:hint token))
                "hint reports the recompile delta (5000-1000)")
            (done))))))

(deftest assemble-degrades-to-unknown-when-jvm-half-nil
  (async done
    (-> (with-jvm-half! nil
          (fn [] (fresh/assemble nil :app browser-half)))
        (.then
          (fn [token]
            (is (= :unknown (:liveness token))
                "nil JVM half ⇒ :unknown, never a crash")
            (is (= "uuid-abc" (:runtime-instance-id token))
                "browser half is still reported on degrade")
            (is (= 1000 (:runtime-loaded-at token)))
            (is (re-find #"LIVENESS UNKNOWN" (:hint token)))
            (is (nil? (:compile-cycle token)) "no JVM fields when the half is absent")
            (done))))))

(deftest assemble-no-runtime
  (async done
    (-> (with-jvm-half! {:compile-cycle 3
                         :build-flushed-at 5000
                         :runtime-count 0
                         :heartbeat-age-ms nil}
          (fn [] (fresh/assemble nil :app browser-half)))
        (.then
          (fn [token]
            (is (= :no-runtime (:liveness token))
                "zero runtimes ⇒ :no-runtime (even though flush > load)")
            (is (re-find #"NO RUNTIME" (:hint token)))
            (done))))))

(deftest token-from-health-extracts-browser-half
  ;; `health` carries the browser-half fields under the same keys; the
  ;; convenience wrapper threads them into assemble.
  (async done
    (let [health {:ok? true
                  :runtime-instance-id "uuid-xyz"
                  :runtime-loaded-at 2000
                  :read-at 3000
                  :frames [:rf/default]}]
      (-> (with-jvm-half! {:compile-cycle 1
                           :build-flushed-at 9000 ; > load 2000
                           :runtime-count 1
                           :heartbeat-age-ms 50}
            (fn [] (fresh/token-from-health nil :app health)))
          (.then
            (fn [token]
              (is (= "uuid-xyz" (:runtime-instance-id token)))
              (is (= 2000 (:runtime-loaded-at token)))
              (is (= :stale-build (:liveness token))
                  "token-from-health threads the browser load time into the stale check")
              (done)))))))
