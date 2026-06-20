(ns re-frame2-pair-mcp.freshness-test
  "Unit tests for the freshness / liveness token.

  The token's job is to make 'the runtime I'm reading is stale /
  disconnected / serving a STALE BUILD' obvious up front. These tests
  pin the cross-check verdict logic (`liveness-verdict`), the
  assembly/merge of the browser + JVM halves (`assemble`), and the
  graceful degradation when the JVM half can't be read.

  The load-bearing case is `:stale-build`: a build whose last flush is
  newer than the moment the browser code loaded."
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

;; ---------------------------------------------------------------------------
;; Actionable liveness on a quiet runtime.
;;
;; A `:liveness :unknown` / `:no-runtime` verdict is the agent's ONLY
;; early warning before a later read returns blank — and the agent can't
;; reload a browser itself. So a non-fresh hint must name the EXACT next
;; HUMAN step: reload `http://localhost:<port>` when the port is known,
;; else restart `shadow-cljs watch <build>`. The `:port` rides into
;; `assemble` (4-arity) / `token-from-health` (4-arity) via the opts map.
;; ---------------------------------------------------------------------------

(deftest unknown-hint-names-the-port-when-known
  (async done
    (-> (with-jvm-half! nil ; nil JVM half ⇒ :unknown
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half {:port 8033})))
        (.then
          (fn [token]
            (is (= :unknown (:liveness token)))
            (is (= 8033 (:port token)) "port rides on the token for the agent to relay")
            (is (re-find #"LIVENESS UNKNOWN" (:hint token)))
            (is (re-find #"ACTION" (:hint token)) "the hint is explicitly actionable")
            (is (re-find #"http://localhost:8033" (:hint token))
                "names the EXACT URL the human reloads")
            (is (re-find #"shadow-cljs watch" (:hint token))
                "names the watch restart as the escalation")
            (done))))))

(deftest unknown-hint-diagnoses-the-zombie-shadow-case
  ;; MULTIPLE / ZOMBIE shadow-cljs JVMs holding the ports keep discover-app
  ;; at :liveness :unknown — reads work (the socket reaches a runtime) but
  ;; the build worker lives in a different JVM, so the worker lookup misses.
  ;; The :unknown hint must NAME that case and recommend the `npx shadow-cljs
  ;; stop` → single-watch remediation that frees the orphan ports.
  (async done
    (-> (with-jvm-half! nil ; nil JVM half ⇒ :unknown
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half {:port 8033})))
        (.then
          (fn [token]
            (is (= :unknown (:liveness token)))
            (is (re-find #"(?i)zombie" (:hint token))
                "names the multiple/zombie-shadow case as the dominant cause")
            (is (re-find #"npx shadow-cljs stop" (:hint token))
                "recommends the remediation that frees the orphan ports")
            (is (re-find #"(?i)one" (:hint token))
                "tells the operator to start exactly ONE watch")
            (done))))))

(deftest unknown-hint-degrades-to-build-name-without-a-port
  (async done
    (-> (with-jvm-half! nil
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half)))
        (.then
          (fn [token]
            (is (= :unknown (:liveness token)))
            (is (not (contains? token :port)) "no port arg ⇒ no :port slot")
            (is (re-find #"ACTION" (:hint token)))
            ;; With no port, the hint still names the build for the watch restart.
            (is (re-find #":examples/machine-epochs" (:hint token))
                "names the build so `shadow-cljs watch <build>` is unambiguous")
            (done))))))

(deftest no-runtime-hint-is-actionable-with-the-port
  ;; The quiet-runtime case: the WS heartbeat is stale → the
  ;; verdict is :no-runtime. The hint must tell the human to reload the
  ;; exact URL, then re-run discover-app.
  (async done
    (-> (with-jvm-half! {:compile-cycle 3
                         :build-flushed-at 500
                         :runtime-count 1
                         :heartbeat-age-ms 60000} ; stale heartbeat ⇒ :no-runtime
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half {:port 8033})))
        (.then
          (fn [token]
            (is (= :no-runtime (:liveness token)))
            (is (re-find #"NO RUNTIME" (:hint token)))
            (is (re-find #"http://localhost:8033" (:hint token))
                ":no-runtime hint names the EXACT URL to reload")
            (is (re-find #"discover-app" (:hint token))
                "tells the agent to re-confirm liveness after the reload")
            (done))))))

(deftest token-from-health-threads-the-port
  ;; The discover-app path uses token-from-health; its 4-arity must carry
  ;; the port through to the hint.
  (async done
    (let [health {:ok? true :runtime-instance-id "uuid-q" :runtime-loaded-at 1 :read-at 2}]
      (-> (with-jvm-half! nil
            (fn [] (fresh/token-from-health nil :examples/machine-epochs health {:port 8033})))
          (.then
            (fn [token]
              (is (= :unknown (:liveness token)))
              (is (= 8033 (:port token)))
              (is (re-find #"http://localhost:8033" (:hint token)))
              (done)))))))

;; ---------------------------------------------------------------------------
;; retry-once-on-nil — one retry before degrading to :unknown.
;;
;; A nil first read of the build-worker state is most often a transient
;; socket hiccup, not a genuinely-unreadable old shadow. One retry
;; recovers the common case so the operator sees the true :fresh /
;; :stale-build / :no-runtime verdict instead of a degraded :unknown. The
;; retry pays a single extra round-trip only on the cold/degraded path.
;;
;; The combinator is pure (takes a Promise-returning thunk), so these
;; tests need NO global var stub — sidestepping the
;; .finally-after-done stub-leak race entirely.
;; ---------------------------------------------------------------------------

(deftest retry-once-recovers-a-transient-blank
  (async done
    (let [calls (atom 0)
          read! (fn []
                  (swap! calls inc)
                  (js/Promise.resolve
                    (if (= 1 @calls)
                      nil ; blank first read (transient hiccup)
                      {:compile-cycle 5 :build-flushed-at 100
                       :runtime-count 1 :heartbeat-age-ms 50})))]
      (-> (fresh/retry-once-on-nil read!)
          (.then
            (fn [half]
              (is (= 2 @calls) "retried exactly once after a blank first read")
              (is (map? half) "the retry recovered the JVM half")
              (is (= 5 (:compile-cycle half)))
              (done)))))))

(deftest retry-once-no-retry-on-first-success
  (async done
    (let [calls (atom 0)
          read! (fn []
                  (swap! calls inc)
                  (js/Promise.resolve {:compile-cycle 7 :runtime-count 1 :heartbeat-age-ms 10}))]
      (-> (fresh/retry-once-on-nil read!)
          (.then
            (fn [half]
              (is (= 1 @calls) "a map-valued first read pays NO retry round-trip")
              (is (= 7 (:compile-cycle half)))
              (done)))))))

(deftest retry-once-persistent-blank-resolves-nil
  (async done
    (let [calls (atom 0)
          read! (fn [] (swap! calls inc) (js/Promise.resolve nil))]
      (-> (fresh/retry-once-on-nil read!)
          (.then
            (fn [half]
              (is (= 2 @calls) "retried once; a persistent blank then degrades")
              (is (nil? half) "two blanks ⇒ nil ⇒ caller degrades to :unknown")
              (done)))))))

(deftest jvm-build-freshness-skips-retry-without-a-socket
  ;; A conn with no live socket short-circuits to nil with NO round-trip
  ;; (and so no retry).
  (async done
    (let [conn (atom {:socket nil :closed? true})]
      (-> (fresh/jvm-build-freshness conn :app)
          (.then (fn [half]
                   (is (nil? half) "no socket ⇒ nil, never a TCP connect")
                   (done)))))))
