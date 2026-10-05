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
            [cljs.reader]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.freshness :as fresh]))

;; ---------------------------------------------------------------------------
;; liveness-verdict — the cross-check.
;; ---------------------------------------------------------------------------

(deftest liveness-verdict-table
  ;; Each row: the verdict, the input, and why. Precedence is part of the
  ;; contract: no JVM half ⇒ :unknown before anything else, and you can't
  ;; be serving stale code if nothing's connected, so :no-runtime beats
  ;; :stale-build. A flush exactly at load time is not stale — the running
  ;; code IS that build — so the staleness comparison is a strict `>`. A
  ;; build with no recorded flush timestamp (never recompiled since boot)
  ;; can't be proven stale.
  (doseq [[expected input why]
          [[:unknown
            {:jvm-read? false :runtime-loaded-at 1000 :build-flushed-at 2000 :runtime-count 1}
            "No JVM half ⇒ :unknown regardless of the browser fields"]
           [:no-runtime
            {:jvm-read? true :runtime-count 1
             :heartbeat-age-ms 60000 ; > 30s threshold
             :build-flushed-at 1000 :runtime-loaded-at 2000}
            "A heartbeat older than the stale threshold ⇒ :no-runtime even with a runtime counted"]
           [:stale-build
            {:jvm-read? true :runtime-count 1 :heartbeat-age-ms 500
             :runtime-loaded-at 1000 :build-flushed-at 5000}
            "Build flushed AFTER the runtime loaded ⇒ :stale-build"]
           [:fresh
            {:jvm-read? true :runtime-count 1 :heartbeat-age-ms 500
             :runtime-loaded-at 5000 :build-flushed-at 1000}
            "Runtime loaded AFTER the last flush ⇒ :fresh (running the current build)"]
           [:fresh
            {:jvm-read? true :runtime-count 1 :heartbeat-age-ms 0
             :runtime-loaded-at 1000 :build-flushed-at 1000}
            "flush == load ⇒ :fresh (not stale)"]
           [:no-runtime
            {:jvm-read? true :runtime-count 0
             :runtime-loaded-at 1000 :build-flushed-at 5000}
            ":no-runtime wins over :stale-build"]
           [:fresh
            {:jvm-read? true :runtime-count 1 :heartbeat-age-ms 100
             :runtime-loaded-at 1000 :build-flushed-at nil}
            "Missing :build-flushed-at can't prove staleness ⇒ :fresh"]
           [:unknown
            {:jvm-read? true :runtime-count 1
             :runtime-loaded-at 5000 :build-flushed-at 1000}
            "A connected runtime with no heartbeat sample is unverified — missing data is never :fresh"]
           [:stale-build
            {:jvm-read? true :runtime-count 1
             :runtime-loaded-at 1000 :build-flushed-at 5000}
            "A missing heartbeat cannot hide a stale build: the flush/load comparison needs no heartbeat"]
           [:unknown
            {:jvm-read? true :worker? false :runtime-count 0}
            "No build worker in the reached JVM ⇒ :unknown — the build's runtimes cannot be counted there"]]]
    (is (= expected (fresh/liveness-verdict input)) why)))

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
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half)))
        (.then
          (fn [token]
            (is (= :unknown (:liveness token))
                "nil JVM half ⇒ :unknown, never a crash")
            (is (= "uuid-abc" (:runtime-instance-id token))
                "browser half is still reported on degrade")
            (is (= 1000 (:runtime-loaded-at token)))
            (is (re-find #"LIVENESS UNKNOWN" (:hint token)))
            (is (nil? (:compile-cycle token)) "no JVM fields when the half is absent")
            (is (not (contains? token :port)) "no port arg ⇒ no :port slot")
            (is (re-find #"ACTION" (:hint token)))
            ;; With no port, the hint still names the build for the watch restart.
            (is (re-find #":examples/machine-epochs" (:hint token))
                "names the build so `shadow-cljs watch <build>` is unambiguous")
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
;; early warning before a later read returns blank. So a non-fresh hint
;; names the EXACT next step: reload `http://localhost:<port>` when the
;; port is known, or the bounded check for what could not be read. The
;; `:port` rides into `assemble` (4-arity) / `token-from-health` (4-arity)
;; via the opts map.
;; ---------------------------------------------------------------------------

(defn- recommends-process-termination?
  "True when `hint` tells the operator to stop, kill or restart shadow-cljs
  processes wholesale. `npx shadow-cljs stop` stops EVERY shadow-cljs
  server on the machine — unrelated projects' watches included — and an
  unreadable build state is no evidence that any of them is a zombie."
  [hint]
  (boolean (or (re-find #"shadow-cljs stop" hint)
               (re-find #"(?i)\bkill\b" hint)
               (re-find #"(?i)zombie" hint)
               (re-find #"(?i)\ball shadow" hint))))

(deftest unknown-hint-is-bounded-and-never-stops-shadow
  ;; A JVM half that cannot be read after the retry. The hint names what
  ;; could not be read and one bounded next step, and never infers zombie
  ;; JVMs or prescribes stopping shadow-cljs processes: nothing in an
  ;; unreadable reply says another process is involved.
  (async done
    (-> (with-jvm-half! nil ; nil JVM half ⇒ :unknown
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half {:port 8033})))
        (.then
          (fn [token]
            (is (= :unknown (:liveness token)))
            (is (= :jvm-unreadable (:unknown-reason token))
                "the token names WHICH half could not be read")
            (is (= 8033 (:port token)) "port rides on the token for the agent to relay")
            (is (re-find #"LIVENESS UNKNOWN" (:hint token)))
            (is (re-find #"ACTION" (:hint token)) "the hint is explicitly actionable")
            (is (re-find #"discover-app" (:hint token)) "the next step is a bounded re-check")
            (is (re-find #":examples/machine-epochs" (:hint token)) "names the build")
            (is (not (recommends-process-termination? (:hint token)))
                (str "an unreadable build state never prescribes stopping shadow-cljs: "
                     (:hint token)))
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
      (-> (with-jvm-half! {:compile-cycle 3 :build-flushed-at 0
                           :runtime-count 0}
            (fn [] (fresh/token-from-health nil :examples/machine-epochs health {:port 8033})))
          (.then
            (fn [token]
              (is (= :no-runtime (:liveness token)))
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

;; ---------------------------------------------------------------------------
;; build-state-jvm-form + jvm-build-freshness-once — the JVM half END TO END
;; on a SUCCESS response.
;;
;; Every `assemble` test above stubs `fresh/jvm-build-freshness` WHOLESALE
;; (`with-jvm-half!`), and the `retry-once-on-nil` tests drive a pure thunk
;; — so those never exercise the ACTUAL JVM-side form
;; (`build-state-jvm-form`) or the round-trip that emits + parses it
;; (`jvm-build-freshness-once`) on a success-shaped response. A malformed
;; form — an unbalanced paren, a fat-fingered token from a bad string
;; concat, a shape that fails to read back the four documented keys —
;; would stay GREEN there because nothing proves
;; the form PARSES or that a realistic JVM payload lands as the
;; {:compile-cycle :build-flushed-at :runtime-count :heartbeat-age-ms} map.
;;
;; These drive the REAL public `fresh/jvm-build-freshness` (threading
;; through the private `jvm-build-freshness-once` + `build-state-jvm-form`)
;; against a socket-bearing conn and a stubbed `nrepl/jvm-eval`, so BOTH the
;; form emission AND the value parse run for real. `jvm-eval` is stubbed to
;; capture the emitted form AND to answer with a realistic success payload —
;; mirroring `port_to_build_test`'s approach for its sibling JVM form
;; (`port->build-jvm-form`).
;; ---------------------------------------------------------------------------

(defn- socket-conn
  "A conn-atom that PASSES `conn-has-socket?` (a live, non-closed socket),
  so `jvm-build-freshness` runs the real JVM round-trip instead of
  short-circuiting to nil. The socket value is inert — the round-trip is
  stubbed at `nrepl/jvm-eval`."
  []
  (atom {:socket :fake-live-socket :closed? false}))

(deftest jvm-build-freshness-emits-a-parseable-well-formed-form
  ;; The parse ratchet: the build-state form the REAL jvm-build-freshness
  ;; emits MUST read back as a single well-formed `(try ...)` s-expression.
  ;; A malformed form (unbalanced parens / a mangled token from a bad string
  ;; concat) throws in `cljs.reader/read-string` here instead of silently
  ;; staying green behind the wholesale stub.
  (async done
    (let [seen (atom nil)
          orig nrepl/jvm-eval
          payload {:worker? true :compile-cycle 3 :build-flushed-at 100
                   :runtimes {7 {:last-pong nil}} :relay-clients {7 {:last-pong 980}} :now 1000}
          stub (fn
                 ([_c form] (reset! seen form)
                            (js/Promise.resolve {:value (pr-str payload)}))
                 ([_c form _o] (reset! seen form)
                               (js/Promise.resolve {:value (pr-str payload)})))]
      (set! nrepl/jvm-eval stub)
      (-> (fresh/jvm-build-freshness (socket-conn) :app)
          (.then
            (fn [_]
              (let [form @seen]
                (is (string? form) "the real jvm-build-freshness emitted a JVM form")
                ;; Substring-pin the shadow internals the form reaches into
                ;; (mirrors port_to_build_test's substring pins on its form).
                (is (re-find #"shadow\.cljs\.devtools\.server\.runtime/get-instance!" form))
                (is (re-find #"shadow\.cljs\.devtools\.server\.supervisor/get-worker" form))
                (is (re-find #":build-state" form))
                (is (re-find #":shadow\.build/build-info" form))
                (is (re-find #":compile-cycle" form))
                (is (re-find #":flush-complete" form))
                (is (re-find #":runtimes" form))
                (is (re-find #":last-pong" form))
                (is (re-find #":relay" form)
                    "the heartbeat source is the relay, which stamps every message a runtime sends")
                (is (re-find #":clients" form))
                (is (not (re-find #"\(apply max" form))
                    (str "the JVM form reduces nothing: an empty heartbeat sample there "
                         "throws an ArityException the catch turns into nil, erasing the "
                         "worker, build and runtime facts with it"))
                (is (re-find #"System/currentTimeMillis" form))
                (is (re-find #"catch Throwable" form)
                    "the form is defended so a missing worker collapses to nil, not a throw")
                (is (re-find #"get-worker sup :app" form)
                    "the build-id is rendered as its :app keyword literal")
                ;; THE PARSE RATCHET: the emitted form reads back as ONE
                ;; well-formed (try (let ...) (catch ...)) s-expression.
                ;; read-string THROWS on an unbalanced / mangled form.
                (let [parsed (cljs.reader/read-string form)]
                  (is (seq? parsed) "the emitted form parses as a single s-expression")
                  (is (= 'try (first parsed))
                      "the form is the defended (try (let ...) (catch ...)) shape")))
              nil))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest jvm-build-freshness-renders-a-namespaced-build-id-literal
  ;; The bid rendering branch for a namespaced keyword build-id — the real
  ;; caller (`assemble`) passes keyword build-ids like :examples/machine-epochs.
  (async done
    (let [seen (atom nil)
          orig nrepl/jvm-eval
          stub (fn
                 ([_c form] (reset! seen form) (js/Promise.resolve {:value "nil"}))
                 ([_c form _o] (reset! seen form) (js/Promise.resolve {:value "nil"})))]
      (set! nrepl/jvm-eval stub)
      (-> (fresh/jvm-build-freshness (socket-conn) :examples/machine-epochs)
          (.then
            (fn [_]
              (is (re-find #"get-worker sup :examples/machine-epochs" @seen)
                  "a namespaced build-id rides as its full keyword literal")
              ;; Still a parseable (try ...) form for the namespaced id.
              (is (= 'try (first (cljs.reader/read-string @seen))))
              nil))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest jvm-build-freshness-parses-a-success-payload-into-the-documented-shape
  ;; A realistic JVM success payload — the raw facts the form projects —
  ;; must parse back into the documented half, proving
  ;; `jvm-build-freshness-once`'s `(some-> (:value resp) cljs.reader/read-string)`
  ;; + `map?` guard read the emitted form's return shape, not a degraded nil.
  (async done
    (let [payload {:worker? true :compile-cycle 12 :build-flushed-at 1699999999999
                   :runtimes {4 {:last-pong nil} 5 {:last-pong nil}}
                   :relay-clients {4 {:last-pong 1700000000958} 5 {:last-pong 1700000000900}}
                   :now 1700000001000}
          orig nrepl/jvm-eval
          stub (fn
                 ([_c _form] (js/Promise.resolve {:value (pr-str payload)}))
                 ([_c _form _o] (js/Promise.resolve {:value (pr-str payload)})))]
      (set! nrepl/jvm-eval stub)
      (-> (fresh/jvm-build-freshness (socket-conn) :app)
          (.then
            (fn [half]
              (is (= {:worker? true :compile-cycle 12 :build-flushed-at 1699999999999
                      :runtime-count 2 :heartbeat-age-ms 42}
                     half)
                  "the facts become the documented half: the freshest of the build's own heartbeats")
              nil))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest jvm-build-freshness-blank-value-degrades-to-nil
  ;; A blank/non-map JVM value (a socket hiccup, an old shadow) must degrade
  ;; to nil through the REAL once + retry so the caller sees :liveness
  ;; :unknown — driven end-to-end, not via the pure-thunk retry tests.
  (async done
    (let [orig nrepl/jvm-eval
          stub (fn
                 ([_c _form] (js/Promise.resolve {:value ""}))
                 ([_c _form _o] (js/Promise.resolve {:value ""})))]
      (set! nrepl/jvm-eval stub)
      (-> (fresh/jvm-build-freshness (socket-conn) :app)
          (.then (fn [half]
                   (is (nil? half) "a blank value ⇒ nil (a non-map read degrades, never throws)")
                   nil))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest assemble-drives-the-real-jvm-half-end-to-end
  ;; The fullest ratchet: `assemble` WITHOUT stubbing jvm-build-freshness —
  ;; only `nrepl/jvm-eval` is stubbed — so the real `build-state-jvm-form`
  ;; is emitted, the real round-trip parses a realistic success payload, and
  ;; the parsed half drives the liveness verdict. A malformed form or a
  ;; shape drift would surface as a WRONG verdict / a dropped field HERE,
  ;; not as a green wholesale stub.
  (async done
    (let [;; flush (500) < load (1000) ⇒ :fresh; runtime connected, hb recent.
          payload {:worker? true :compile-cycle 9 :build-flushed-at 500
                   :runtimes {7 {:last-pong nil}} :relay-clients {7 {:last-pong 1900}}
                   :now 2000}
          orig nrepl/jvm-eval
          stub (fn
                 ([_c _form] (js/Promise.resolve {:value (pr-str payload)}))
                 ([_c _form _o] (js/Promise.resolve {:value (pr-str payload)})))]
      (set! nrepl/jvm-eval stub)
      (-> (fresh/assemble (socket-conn) :app browser-half)
          (.then
            (fn [token]
              (is (= "uuid-abc" (:runtime-instance-id token)) "browser id carried through")
              (is (= 1000 (:runtime-loaded-at token)) "browser load time carried through")
              (is (= :app (:build-id token)) "build-id stamped")
              (is (= 9 (:compile-cycle token)) "the REAL JVM half's compile-cycle merged in")
              (is (= 500 (:build-flushed-at token)))
              (is (= 1 (:runtime-count token)))
              (is (= :fresh (:liveness token))
                  "load (1000) > flush (500), runtime connected ⇒ :fresh via the REAL form")
              (is (nil? (:hint token)) "a fresh verdict carries no hint")
              nil))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; The heartbeat belongs to the SELECTED build's own runtimes.
;;
;; The fixtures are shaped like shadow-cljs 3.4.10's own state. The build
;; worker's `:runtimes` map is keyed by relay client id and holds each
;; runtime's client-info; its `:last-pong` is written only by a
;; `:cljs-repl-pong` reply, which that shadow-cljs never solicits, so it
;; stays absent on a healthy tab. The relay's `:clients` map is keyed by
;; the SAME client ids, holds every client the relay serves — other
;; builds' tabs, tools, the CLJ runtime — and stamps `:last-pong` on every
;; message a client sends, including its pongs to the relay's idle pings.
;; `jvm-reply` projects the two exactly as `build-state-jvm-form` does on
;; the JVM, so each row drives the real decode, match and verdict with
;; only `nrepl/jvm-eval` stubbed.
;; ---------------------------------------------------------------------------

(def ^:private now-ms 1791177296969)

(def ^:private flushed-at 1791176095823)

(defn- browser-runtime
  "A build worker `:runtimes` entry for a connected browser tab, as
  shadow's `add-runtime` stores it: the client-info plus `:client-id`."
  [client-id]
  {:client-id  client-id
   :host       :browser
   :lang       :cljs
   :build-id   :app
   :proc-id    "8e55e886-374f-4cf6-9c12-09ea4611a749"
   :user-agent "Chrome"})

(defn- relay-client
  "A relay `:clients` entry, as shadow's local relay stores it."
  [client-id last-pong]
  {:client-id   client-id
   :client-info {:type :runtime :lang :cljs}
   :last-ping   (- last-pong 3)
   :last-pong   last-pong})

(defn- worker-state
  "A build worker's state-ref value carrying `runtimes`."
  [runtimes]
  {:build-state {:shadow.build/build-info {:compile-cycle 1 :flush-complete flushed-at}}
   :runtimes    runtimes})

(defn- jvm-reply
  "What `build-state-jvm-form` evaluates to for a worker whose state-ref
  holds `worker` (nil when the JVM has no worker for the build) and a
  relay serving `clients`, read at `now-ms`."
  [worker clients]
  (let [pongs (fn [m] (into {} (map (fn [[id x]] [id {:last-pong (:last-pong x)}])) m))
        info  (get-in worker [:build-state :shadow.build/build-info])]
    {:worker?          (some? worker)
     :compile-cycle    (:compile-cycle info)
     :build-flushed-at (:flush-complete info)
     :runtimes         (pongs (:runtimes worker))
     :relay-clients    (pongs clients)
     :now              now-ms}))

(def ^:private live-browser
  "A tab that loaded AFTER the last flush, so only the heartbeat decides."
  {:runtime-instance-id "uuid-live"
   :runtime-loaded-at   (+ flushed-at 600000)
   :read-at             (- now-ms 5)})

(defn- token-for
  "The token `assemble` builds when the JVM answers `reply`."
  [reply]
  (let [orig nrepl/jvm-eval
        resp {:value (pr-str reply)}
        stub (fn
               ([_c _form] (js/Promise.resolve resp))
               ([_c _form _o] (js/Promise.resolve resp)))]
    (set! nrepl/jvm-eval stub)
    (-> (fresh/assemble (socket-conn) :app live-browser {:port 8280})
        (.finally (fn [] (tu/restore-jvm-eval! stub orig))))))

(def ^:private unrelated-client
  "A relay client that is NOT one of this build's runtimes — another
  build's tab or a tool — that answered moments ago."
  (relay-client 9 (- now-ms 10)))

(def ^:private heartbeat-rows
  [["no build worker in the reached JVM"
    (jvm-reply nil {9 unrelated-client})
    {:liveness :unknown :unknown-reason :no-build-worker :runtime-count 0}]

   ["a worker with zero runtimes"
    (jvm-reply (worker-state {}) {9 unrelated-client})
    {:liveness :no-runtime :runtime-count 0 :compile-cycle 1 :build-flushed-at flushed-at}]

   ["one connected runtime with no heartbeat anywhere"
    (jvm-reply (worker-state {7 (browser-runtime 7)}) {9 unrelated-client})
    {:liveness :unknown :unknown-reason :heartbeat-unavailable
     :runtime-count 1 :compile-cycle 1 :build-flushed-at flushed-at}]

   ["one connected runtime without a worker :last-pong, matched to a recent relay heartbeat"
    (jvm-reply (worker-state {7 (browser-runtime 7)})
               {7 (relay-client 7 (- now-ms 2533)) 9 unrelated-client})
    {:liveness :fresh :heartbeat-age-ms 2533 :runtime-count 1 :compile-cycle 1}]

   ["a matched relay heartbeat older than the stale threshold"
    (jvm-reply (worker-state {7 (browser-runtime 7)})
               {7 (relay-client 7 (- now-ms 45000)) 9 unrelated-client})
    {:liveness :no-runtime :heartbeat-age-ms 45000 :runtime-count 1}]

   ["a fresh heartbeat on unrelated relay clients only"
    (jvm-reply (worker-state {7 (browser-runtime 7)})
               {9 unrelated-client 1 (relay-client 1 (- now-ms 2))})
    {:liveness :unknown :unknown-reason :heartbeat-unavailable :runtime-count 1}]

   ["future and non-numeric timestamps on the build's own runtime"
    (jvm-reply (worker-state {7 (assoc (browser-runtime 7) :last-pong "soon")})
               {7 (relay-client 7 (+ now-ms 60000))})
    {:liveness :unknown :unknown-reason :heartbeat-unavailable :runtime-count 1}]

   ["a worker :last-pong from a shadow-cljs that still writes one"
    (jvm-reply (worker-state {7 (assoc (browser-runtime 7) :last-pong (- now-ms 800))}) {})
    {:liveness :fresh :heartbeat-age-ms 800 :runtime-count 1}]])

(deftest heartbeat-comes-only-from-the-selected-builds-runtimes
  ;; A fresh heartbeat on a relay client that is not one of the build's
  ;; runtimes says nothing about the build's tab, and a connected runtime
  ;; with no usable heartbeat is unverified rather than fresh. Each row
  ;; keeps the worker / build / runtime facts it has, and no non-fresh
  ;; hint prescribes stopping shadow-cljs.
  (async done
    (-> (reduce
          (fn [p [why reply expected]]
            (.then p
                   (fn [_]
                     (.then (token-for reply)
                            (fn [token]
                              (doseq [[k v] expected]
                                (is (= v (get token k)) (str why " — " k)))
                              (when-not (contains? expected :heartbeat-age-ms)
                                (is (not (contains? token :heartbeat-age-ms))
                                    (str why " — no usable sample, so no age is reported")))
                              (if (= :fresh (:liveness token))
                                (is (nil? (:hint token))
                                    (str why " — a fresh verdict carries no hint"))
                                (is (not (recommends-process-termination? (str (:hint token))))
                                    (str why " — " (:hint token)))))))))
          (js/Promise.resolve nil)
          heartbeat-rows)
        (.catch (fn [e] (is false (str "assembling a token rejected: " e))))
        (.then (fn [_] (done))))))
