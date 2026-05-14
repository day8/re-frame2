(ns re-frame-pair2-mcp.probe-test
  "Unit tests for the runtime preload probe + per-socket probe cache
  (rf2-sjpx0).

  The probe runs one bencode round-trip per non-streaming tool entry
  to confirm the pair2 runtime is loaded into the consumer build
  before any tool form is sent. Audit rf2-sjpx0 surfaced that this
  fixed cost doubles cheap-read latency — once the preload is
  confirmed for a given `(conn, build-id)` pair, it cannot un-load
  without socket teardown, so subsequent probes are pure waste.

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
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.probe :as probe]))

;; ---------------------------------------------------------------------------
;; Stub harness — install a counting cljs-eval-value, restore in finally.
;; ---------------------------------------------------------------------------

(defn- with-stubbed-eval!
  "Install a stub `cljs-eval-value` that resolves to `canned-value` on
  every call, counting invocations into `call-count*`. Run `body-fn`
  (returning a Promise) and restore in `.finally` so cleanup outlives
  async resolution. Mirror of `conformance_test/with-stubbed-eval!`."
  [canned-value call-count* body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id _form-str]
                (swap! call-count* inc)
                (js/Promise.resolve canned-value))
               ([_conn _build-id _form-str _opts]
                (swap! call-count* inc)
                (js/Promise.resolve canned-value)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

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
  ;; rf2-sjpx0: a confirmed positive probe MUST short-circuit on the
  ;; next call for the same (conn, build-id). The second call must
  ;; resolve true WITHOUT incrementing the eval counter.
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
  ;; Negative path still surfaces the structured ex-info — cache only
  ;; affects positive-result hot path.
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
                              (is (= :runtime-not-preloaded (:reason data)))
                              (is (string? (:hint data))))))
                  (.then (fn [_] nil)))))
          (.then (fn [_] (done)))))))
