(ns re-frame2-pair-mcp.probe-test
  "Unit tests for the runtime preload probe + per-socket probe cache.

  The probe runs one bencode round-trip per non-streaming tool entry
  to confirm the re-frame2-pair runtime is loaded into the consumer build
  before any tool form is sent. That fixed cost would double cheap-read
  latency on every call, so it is cached: once the preload is confirmed
  for a given `(conn, build-id)` pair, it cannot un-load without socket
  teardown, so subsequent probes would be pure waste.

  The cache:

  - Lives on the conn-atom's `:probed-builds` set.
  - Is populated on the first positive probe for a build.
  - Resolves synchronously from cache thereafter.
  - Is reset on (re)connect and on `close!` so a page reload
    triggers a fresh probe on the next call.
  - Caches POSITIVE results only — a missing preload re-probes on
    each subsequent call so a freshly-added preload lands without a
    server restart.

  Tests stub `nrepl/cljs-eval-value` with a side-effect-counting
  fn so we can directly assert how many round-trips the probe
  costs across N tool calls."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.reader :as edn]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.freshness :as freshness]
            [re-frame2-pair-mcp.tools.probe :as probe]))

;; ---------------------------------------------------------------------------
;; Stub harness — install a counting cljs-eval-value, restore in finally.
;; ---------------------------------------------------------------------------

(defn- with-stubbed-eval!
  "Install a stub `cljs-eval-value` that resolves to `canned-value` on
  every call, counting invocations into `call-count*`. Run `body-fn`
  (returning a Promise) and restore in `.finally` so cleanup outlives
  async resolution. Mirror of `conformance_test/with-stubbed-eval!`.

  Also stubs `nrepl/jvm-eval` minimally so the preload-failure
  diagnostic ladder runs to the `:runtime-loaded-but-preload-missing`
  rung (runtime alive, marker absent). Without the JVM stub the ladder
  would short-circuit on `:nrepl-unreachable` for every negative cljs
  probe."
  [canned-value call-count* body-fn]
  (let [orig-cljs nrepl/cljs-eval-value
        orig-jvm  nrepl/jvm-eval
        cljs-stub (fn
                    ([_conn _build-id _form-str]
                     (swap! call-count* inc)
                     (js/Promise.resolve canned-value))
                    ([_conn _build-id _form-str _opts]
                     (swap! call-count* inc)
                     (js/Promise.resolve canned-value)))
        jvm-stub  (fn
                    ([_conn form-str]
                     (js/Promise.resolve
                       (if (re-find #"active-builds" form-str)
                         {:value "[:app]"}
                         {:value "1"})))
                    ([_conn form-str _opts]
                     (js/Promise.resolve
                       (if (re-find #"active-builds" form-str)
                         {:value "[:app]"}
                         {:value "1"}))))]
    (set! nrepl/cljs-eval-value cljs-stub)
    (set! nrepl/jvm-eval         jvm-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-eval! cljs-stub orig-cljs)
                    (tu/restore-jvm-eval! jvm-stub orig-jvm))))))

;; ---------------------------------------------------------------------------
;; Conn fixture — a real conn-atom (not a stubbed one). The probe
;; cache lives on the atom; we want to exercise it the way the real
;; server does.
;; ---------------------------------------------------------------------------

(defn- fresh-conn []
  ;; Port/host irrelevant — we never actually open the socket; the
  ;; stub intercepts `cljs-eval-value` before any network call.
  ;; We DO need :probed-builds to be reset like a fresh-connected
  ;; socket: simulate the `connect!` on-connect handler's behaviour.
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{})
    conn))

;; ---------------------------------------------------------------------------
;; The contract.
;; ---------------------------------------------------------------------------

(deftest first-positive-probe-runs-one-eval
  ;; Sanity: the first call to a fresh conn issues exactly one
  ;; cljs-eval-value round-trip and resolves true.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/runtime-preloaded? conn :app)
                  (.then (fn [ok?]
                           (is (true? ok?))
                           (is (= 1 @calls)))))))
          (.then (fn [_] (done)))))))

(deftest second-positive-probe-is-cached
  ;; A confirmed positive probe MUST short-circuit on the next call for
  ;; the same (conn, build-id). The second call must resolve true
  ;; WITHOUT incrementing the eval counter.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/runtime-preloaded? conn :app)
                  (.then (fn [_]
                           (probe/runtime-preloaded? conn :app)))
                  (.then (fn [ok?]
                           (is (true? ok?))
                           (is (= 1 @calls)
                               "Second probe must NOT issue a new eval"))))))
          (.then (fn [_] (done)))))))

(deftest distinct-builds-probe-independently
  ;; Cache is keyed on `(conn, build-id)`. Two builds on the same
  ;; socket each pay their own one-time probe; neither is a hit on
  ;; the other.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/runtime-preloaded? conn :app)
                  (.then (fn [_] (probe/runtime-preloaded? conn :other)))
                  (.then (fn [_] (probe/runtime-preloaded? conn :app)))
                  (.then (fn [_] (probe/runtime-preloaded? conn :other)))
                  (.then (fn [_]
                           (is (= 2 @calls)
                               "Each build probes once; subsequent hits cache"))))))
          (.then (fn [_] (done)))))))

(deftest negative-probe-is-not-cached
  ;; Negative result MUST re-probe on the next call. A missing preload
  ;; usually surfaces on the very first call; re-probing on subsequent
  ;; calls lets a freshly-added preload land without a server restart.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! false calls
            (fn []
              (-> (probe/runtime-preloaded? conn :app)
                  (.then (fn [ok?]
                           (is (false? ok?))
                           (probe/runtime-preloaded? conn :app)))
                  (.then (fn [ok?]
                           (is (false? ok?))
                           (is (= 2 @calls)
                               "Negative result must re-probe"))))))
          (.then (fn [_] (done)))))))

(deftest cache-resets-on-close
  ;; `nrepl/close!` drops `:probed-builds`. A subsequent probe issues
  ;; a fresh round-trip — the post-close conn could have reconnected
  ;; to a different build that hadn't yet loaded the preload.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/runtime-preloaded? conn :app)
                  (.then (fn [_]
                           ;; Simulate socket close.
                           (swap! conn assoc :probed-builds #{} :closed? true)
                           (probe/runtime-preloaded? conn :app)))
                  (.then (fn [ok?]
                           (is (true? ok?))
                           (is (= 2 @calls)
                               "Probe must re-run after close clears the cache"))))))
          (.then (fn [_] (done)))))))

(deftest probe-cache-defensive-on-nil-conn
  ;; Conformance tests pass a nil conn through `tools/invoke`. The
  ;; cache helpers MUST NOT throw on a non-atom conn — they simply
  ;; skip the cache and always probe via the stub.
  (async done
    (let [calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/runtime-preloaded? nil :app)
                  (.then (fn [ok?]
                           (is (true? ok?))
                           (probe/runtime-preloaded? nil :app)))
                  (.then (fn [ok?]
                           (is (true? ok?))
                           (is (= 2 @calls)
                               "nil conn cannot cache — every call probes"))))))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; `ensure-runtime!` wiring — the per-tool entry point.
;; ---------------------------------------------------------------------------

(deftest ensure-runtime-shares-the-cache
  ;; The five non-streaming tools call `ensure-runtime!`, which
  ;; delegates to `runtime-preloaded?`. The cache benefit MUST flow
  ;; through — one round-trip across many `ensure-runtime!` calls
  ;; for the same conn+build.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/ensure-runtime! conn :app)
                  (.then (fn [_] (probe/ensure-runtime! conn :app)))
                  (.then (fn [_] (probe/ensure-runtime! conn :app)))
                  (.then (fn [_]
                           (is (= 1 @calls)
                               "Three ensure-runtime! calls, one probe")))
                  (.catch (fn [err]
                            (is false (str "ensure-runtime! rejected: "
                                           (.-message err))))))))
          (.then (fn [_] (done)))))))

(deftest ensure-runtime-rejects-on-missing-preload
  ;; Negative path surfaces a structured ex-info — cache only
  ;; affects positive-result hot path.
  ;;
  ;; The rejection reason is `:runtime-loaded-but-preload-missing` (the
  ;; specific rung of the diagnostic ladder for the case where the JVM
  ;; is reachable, the build is running, a CLJS runtime is connected,
  ;; and ONLY the marker is absent). The probe-test stub returns
  ;; `false` for the marker query (runtime alive, marker absent) → the
  ;; ladder lands on this rung.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! false calls
            (fn []
              (-> (probe/ensure-runtime! conn :app)
                  (.then (fn [_]
                           (is false "ensure-runtime! must reject when preload missing")))
                  (.catch (fn [err]
                            (let [data (ex-data err)]
                              (is (= :runtime-loaded-but-preload-missing (:reason data)))
                              (is (string? (:hint data)))
                              (is (= :app (:build data))
                                  "rejection carries the build id for the operator's next move"))))
                  (.then (fn [_] nil)))))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; Diagnostic ladder — pin each rung end-to-end.
;; ---------------------------------------------------------------------------

(defn- with-tri-stub!
  "Stub harness with finer control over both `cljs-eval-value` and
  `jvm-eval`. Used to drive each rung of the diagnostic ladder. Both
  are a fn `(fn [form-str] -> canned)` so the test can branch on the
  form being eval'd."
  [cljs-fn jvm-fn body-fn]
  (let [orig-cljs nrepl/cljs-eval-value
        orig-jvm  nrepl/jvm-eval
        cljs-stub (fn
                    ([_conn _build-id form-str]      (js/Promise.resolve (cljs-fn form-str)))
                    ([_conn _build-id form-str _opts] (js/Promise.resolve (cljs-fn form-str))))
        jvm-stub  (fn
                    ([_conn form-str]      (js/Promise.resolve (jvm-fn form-str)))
                    ([_conn form-str _opts] (js/Promise.resolve (jvm-fn form-str))))]
    (set! nrepl/cljs-eval-value cljs-stub)
    (set! nrepl/jvm-eval         jvm-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-eval! cljs-stub orig-cljs)
                    (tu/restore-jvm-eval! jvm-stub orig-jvm))))))

(defn- assert-ladder-rejects-with-reason
  "Drive `ensure-runtime!` to the rejection path and assert the rung's
  reason + hint pattern. Centralises the deeply-nested Promise chain
  shape so each rung test reads as data."
  [conn expected-reason hint-pattern done extra-assertions]
  (-> (probe/ensure-runtime! conn :app)
      (.then  (fn [_] (is false "must reject") (done)))
      (.catch (fn [err]
                (let [data (ex-data err)]
                  (is (= expected-reason (:reason data)))
                  (is (re-find hint-pattern (:hint data)))
                  (when extra-assertions (extra-assertions data))
                  (done))))))

(deftest diagnose-rung-nrepl-unreachable
  (testing "JVM eval returns garbage (not \"1\") → :nrepl-unreachable"
    (async done
      (with-tri-stub! (fn [_] false)
                      (fn [_] {:value "dead"})
        (fn []
          (assert-ladder-rejects-with-reason
            (fresh-conn) :nrepl-unreachable #"nREPL" done nil))))))

(deftest diagnose-rung-build-not-running
  (testing "build is not in active-builds → :build-not-running with :running-builds"
    (async done
      (with-tri-stub! (fn [_] false)
                      (fn [form-str]
                        (if (re-find #"active-builds" form-str)
                          {:value "[:other]"}
                          {:value "1"}))
        (fn []
          (assert-ladder-rejects-with-reason
            (fresh-conn) :build-not-running #":other" done
            (fn [data]
              (is (= [:other] (:running-builds data))))))))))

(deftest diagnose-rung-no-runtime-connected
  (testing "build is running but cljs-eval returns blank/nil → :no-runtime-connected"
    (async done
      (with-tri-stub! (fn [_] nil)
                      (fn [form-str]
                        (if (re-find #"active-builds" form-str)
                          {:value "[:app]"}
                          {:value "1"}))
        (fn []
          (assert-ladder-rejects-with-reason
            (fresh-conn) :no-runtime-connected #"no CLJS runtime" done nil))))))

(deftest diagnose-rung-runtime-loaded-but-preload-missing
  (testing "cljs eval returns false → :runtime-loaded-but-preload-missing"
    (async done
      (with-tri-stub! (fn [_] false)
                      (fn [form-str]
                        (if (re-find #"active-builds" form-str)
                          {:value "[:app]"}
                          {:value "1"}))
        (fn []
          (assert-ladder-rejects-with-reason
            (fresh-conn) :runtime-loaded-but-preload-missing #"preload" done nil))))))

;; ---------------------------------------------------------------------------
;; Liveness re-validation (rf2-dk6bv5).
;;
;; A positively-cached marker probe (`:probed-builds`) is scoped to the
;; nREPL socket, which stays open independently of the browser tab's own
;; WebSocket. If the tab closes/crashes/navigates away AFTER the marker
;; was cached, `runtime-preloaded?` alone would keep resolving true
;; forever — `ensure-runtime!` would wave every later call straight
;; through to a real eval that comes back blank, and the caller would
;; read that blank as a legitimate nil (`{:ok? true :value nil}`),
;; indistinguishable from a genuine nil, with the diagnostic ladder
;; unreachable (it only runs when the marker probe itself is false).
;;
;; `ensure-runtime!` closes this by routing through `confirmed-live?`,
;; which pairs the (still-cached) marker probe with a cheap JVM-side
;; liveness read — `freshness/jvm-build-freshness`'s `:runtime-count` —
;; on EVERY call. `freshness/jvm-build-freshness` uses `nrepl/jvm-eval`
;; (a DIFFERENT round-trip from the browser marker eval `cljs-eval-value`
;; probes), so stubbing it independently lets these tests simulate the
;; runtime going blank WITHOUT touching the marker cache itself.
;; ---------------------------------------------------------------------------

(defn- with-stubbed-freshness-sequence!
  "Stub `freshness/jvm-build-freshness` to resolve to each of `values` in
  turn (one per call; the last value repeats once `values` is
  exhausted), counting invocations into `calls*`. Restored in `.finally`
  via the identity-guarded `tu/restore-freshness!`."
  [values calls* body-fn]
  (let [orig freshness/jvm-build-freshness
        n    (count values)
        stub (fn [_conn _build-id]
               (let [i (swap! calls* inc)]
                 (js/Promise.resolve (nth values (min (dec i) (dec n))))))]
    (set! freshness/jvm-build-freshness stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-freshness! stub orig))))))

(deftest ensure-runtime-revalidates-a-previously-cached-positive-probe
  ;; THE acceptance test: a runtime confirmed live once must NOT be
  ;; trusted forever from the marker cache. The first `ensure-runtime!`
  ;; call succeeds and caches the marker; between it and the second call
  ;; the browser tab goes away (`:runtime-count` drops to a DEFINITIVE
  ;; 0). The second call must reject via the diagnostic ladder — NOT
  ;; resolve as if the runtime were still live.
  (async done
    (let [conn            (fresh-conn)
          eval-calls      (atom 0)
          freshness-calls (atom 0)
          ;; The marker-eval stub: true on the very first call (caches
          ;; the positive marker); nil (blank) thereafter — faithful to
          ;; a genuinely-disconnected browser, which is what the ladder's
          ;; own re-check would also see in production once the tab is
          ;; actually gone.
          cljs-fn         (fn [_form]
                            (let [n (swap! eval-calls inc)]
                              (if (= 1 n) true nil)))
          jvm-fn          (fn [form-str]
                            (if (re-find #"active-builds" form-str)
                              {:value "[:app]"}
                              {:value "1"}))
          drive!          (fn []
                            (-> (probe/ensure-runtime! conn :app)
                                (.then (fn [_]
                                         ;; First call: still connected — resolves.
                                         (probe/ensure-runtime! conn :app)))
                                (.then (fn [_]
                                         (is false "second ensure-runtime! must reject once the tab disconnects")))
                                (.catch (fn [err]
                                          (let [data (ex-data err)]
                                            (is (= :no-runtime-connected (:reason data))
                                                "the liveness re-check's :runtime-count 0 routes to the precise reason")
                                            (is (= :app (:build data))))))))]
      (-> (with-stubbed-freshness-sequence!
            [{:runtime-count 1} {:runtime-count 0}] freshness-calls
            (fn [] (with-tri-stub! cljs-fn jvm-fn drive!)))
          (.then (fn [_]
                   (is (= 2 @freshness-calls)
                       "the liveness read re-runs on EVERY ensure-runtime! call, cache hit or not")
                   (done)))))))

(deftest ensure-runtime-cache-hit-stays-live-when-jvm-confirms-runtime
  ;; Control: a cache hit whose liveness re-check confirms the runtime is
  ;; STILL connected (:runtime-count > 0) must keep resolving — the fix
  ;; must not turn every cache hit into a spurious rejection.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-freshness-sequence! [{:runtime-count 1}] (atom 0)
            (fn []
              (with-stubbed-eval! true calls
                (fn []
                  (-> (probe/ensure-runtime! conn :app)
                      (.then (fn [_] (probe/ensure-runtime! conn :app)))
                      (.then (fn [_] (probe/ensure-runtime! conn :app)))
                      (.catch (fn [err]
                                (is false (str "must not reject: " (.-message err))))))))))
          (.then (fn [_]
                   (is (= 1 @calls)
                       "the marker probe is still cache-hit — only the JVM liveness read re-runs")
                   (done)))))))

(deftest ensure-runtime-cache-hit-tolerant-of-unreadable-jvm-half
  ;; An unreadable JVM half (nil — old shadow, a socket hiccup, or, as in
  ;; every OTHER test in this suite, a conn with no live :socket at all)
  ;; is INCONCLUSIVE, not proof of disconnection. It must never
  ;; false-positive a live runtime as gone — this is the default
  ;; behaviour every other test in this file already relies on (their
  ;; `fresh-conn` carries no socket, so `jvm-build-freshness` short-
  ;; circuits to nil without a round-trip); this test pins it explicitly.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/ensure-runtime! conn :app)
                  (.then (fn [v]
                           (is (nil? v)
                               "resolves nil (success) when the JVM half is unreadable")))
                  (.catch (fn [err]
                            (is false (str "must not reject on an unreadable JVM half: "
                                           (.-message err))))))))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; `err->result`. A runtime/preflight/transport REJECTION is a known-tool
;; failure with `:ok? false`; per the MCP error-handling contract
;; (API §Result shape; 001-Wire-Protocol §JSON-RPC error codes) it MUST
;; surface as `isError: true`, NOT a success-shaped `ok-text` (which a host
;; reads as a value and the response cache could store, masking later reads).
;; ---------------------------------------------------------------------------

(defn- result-edn
  "Read the EDN payload back off an MCP result envelope."
  [^js result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))
        text    (when item (j/get item :text))]
    (some-> text edn/read-string)))

(deftest err->result-structured-rejection-is-error
  ;; A structured ex-info (the shape resolve-and-preflight! rejects with)
  ;; surfaces its reason verbatim AND carries :isError true.
  (let [rej (ex-info "no re-frame2-pair runtime for build"
                     {:reason :no-runtime-for-build :build :app
                      :running-builds [:other]})
        out (probe/err->result :snapshot-failed rej)]
    (is (true? (j/get out :isError))
        "structured preflight rejection is :isError true")
    (let [edn (result-edn out)]
      (is (false? (:ok? edn)))
      (is (= :no-runtime-for-build (:reason edn)) "ex-info reason rides verbatim")
      (is (= :app (:build edn)))
      (is (= [:other] (:running-builds edn))
          "the running-build enumeration survives so the operator can re-aim"))))

(deftest err->result-bare-rejection-is-error-with-fallback-reason
  ;; A plain (non-ex-info) rejection falls through to the fallback-reason
  ;; shape — still :isError true, carrying the error message.
  (let [out (probe/err->result :watch-until-failed (js/Error. "socket boom"))]
    (is (true? (j/get out :isError))
        "bare rejection is also :isError true")
    (let [edn (result-edn out)]
      (is (false? (:ok? edn)))
      (is (= :watch-until-failed (:reason edn))
          "fallback-reason keys the generic shape")
      (is (= "socket boom" (:message edn))))))
