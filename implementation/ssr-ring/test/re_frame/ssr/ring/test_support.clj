(ns re-frame.ssr.ring.test-support
  "Shared JVM test scaffolding for the ssr-ring suite.

  This namespace single-sources ephemeral Jetty lifecycle, JDK HTTP requests,
  streaming-thread leak detection, and per-test runtime reset. Timeouts and
  polling cadence remain explicit at call sites.

  Independence note: requiring this ns does NOT co-load any test
  namespace (it depends only on production `re-frame.*` source +
  `ring.adapter.jetty` + JDK classes), so every test ns stays
  independently runnable under the artefact `:test` alias.

  Contract smoke coverage lives in `test_support_contract_test.clj`.

  ## Per-test reset fixture

  `reset-runtime` is the artefact-local `:each` reset for the ssr-ring
  JVM suite. The substrate-agnostic
  `re-frame.test-support/make-reset-runtime-fixture` does the
  registrar / frames / flows / schemas / machines / routing reset and
  installs the SSR adapter; the four SSR per-request side-channel atoms
  (Spec 011 §Per-request frame teardown) live in `re-frame.ssr.*`
  production namespaces (ssr/src), reachable through the existing
  `day8/re-frame2-ssr` dependency. The common reset remains in core; only
  the four SSR-specific atom resets are local, fired through the fixture's
  `:init-fn` seam (runs after adapter install, under the ambient
  `:rf/default` scope, before each test body)."
  (:require [ring.adapter.jetty :as jetty]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]
            [re-frame.test-support :as test-support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [org.eclipse.jetty.server Server]))

;; ===========================================================================
;; Per-test reset fixture
;; ===========================================================================

(defn- reset-ssr-runtime!
  "The SSR-specific reset the published core fixture does NOT cover, run
  through the fixture's `:init-fn` seam (after adapter install + the
  fixture's registrar/listener clears, under the ambient `:rf/default`
  scope, before each test body). Two parts:

  1. Clear the four per-request SSR side-channel atoms (Spec 011
     §Per-request frame teardown). All four are keyed by frame-id; a stale
     entry from a prior test would otherwise bleed process-wide. Mirrors
     what the runtime's per-request frame teardown hook
     (`re-frame.ssr/on-frame-destroyed!`) clears at end-of-request:
       - `request/request-slots`               — the active HTTP request
       - `response/response-slots`             — the HTTP response accumulator
       - `error-listener/pending-error-traces` — per-frame error-trace buffer

     These atoms ship in `re-frame.ssr.*` production source, reached
     directly (the same atoms the private façade aliases hold) — no
     external test path needed.

  2. Reinstate the namespace-load listener and late-bind registrations the
     SSR / routing / head / machines namespaces install at load. The
     published fixture clears the trace-tooling + event-emit listener
     registries each test (`trace-tooling/clear-listeners!` /
     `event-emit/clear-event-listeners!`), which drops the always-on SSR
     `::error-projection` error-emit listener and its dev-trace twin; without
     them a render-/drain-
     time throw recovers to a silent HTTP 200 instead of the fail-closed
     5xx the SSR contract mandates. A `:reload` re-runs each ns body so
     those listeners — and the routing late-bind hooks `reg-route`
     resolves through (`:routing/reg-route`, Spec 012) — resurrect. This
     restores them."
  []
  (reset! request/request-slots {})
  (reset! response/response-slots {})
  (reset! error-listener/pending-error-traces {})
  (require 're-frame.routing  :reload)
  (require 're-frame.ssr      :reload)
  (require 're-frame.ssr.head :reload)
  (require 're-frame.machines :reload))

(def ^:private reset-runtime-fixture
  "The published, substrate-agnostic per-test reset
  (`re-frame.test-support/make-reset-runtime-fixture`) configured for the
  ssr-ring JVM suite: it installs the SSR substrate adapter (which also
  ensures the ordinary `:rf/default` app frame and binds it as the body's
  ambient scope) and resets the registrar / frames / flows / schemas /
  machines / routing around each test. `:init-fn` layers the SSR-specific
  side-channel resets on top, fired after adapter install and before the
  test body under the same ambient `:rf/default` scope. Built once at
  ns-load so the registrar baseline is pinned to THIS suite's ns-load
  registrations for run-order independence."
  (test-support/make-reset-runtime-fixture
    {:adapter ssr/adapter
     :init-fn reset-ssr-runtime!}))

(defn reset-runtime
  "The canonical `:each` fixture for the ssr-ring JVM suite. Wipes the
  registrar, every
  per-frame SSR side-channel atom, and installs the SSR adapter + ensures
  the ambient `:rf/default` frame."
  [test-fn]
  (reset-runtime-fixture test-fn))

;; ===========================================================================
;; Ephemeral Jetty host
;; ===========================================================================

(defn start-jetty!
  "Run `handler` under Jetty on an ephemeral port and return
  `{:server <Server> :port <int>}`.

    :port 0                  — OS-assigned ephemeral port (the test reads
                               the bound port back off the Server).
    :host \"127.0.0.1\"        — loopback only; never bind a public
                               interface in CI.
    :join? false             — this thread does not block on the server.
    :send-server-version? false / :send-date-header? false
                             — trim ambient response-header noise so
                               header assertions see only what the
                               adapter emitted."
  [handler]
  (let [^Server server (jetty/run-jetty handler
                                        {:port                 0
                                         :host                 "127.0.0.1"
                                         :join?                false
                                         :send-server-version? false
                                         :send-date-header?    false})
        port (.. server (getURI) (getPort))]
    {:server server :port port}))

(defn stop-jetty!
  "Stop a Jetty `Server` returned by `start-jetty!`."
  [^Server server]
  (.stop server))

(defmacro with-jetty
  "Bind `port-sym` to the ephemeral port of a Jetty server running
  `handler-expr`, evaluate `body`, and stop the server in `finally`.
  `bindings` is `[port-sym handler-expr]`."
  [[port-sym handler-expr] & body]
  `(let [{server# :server port# :port} (start-jetty! ~handler-expr)
         ~port-sym port#]
     (try
       ~@body
       (finally
         (stop-jetty! server#)))))

;; ===========================================================================
;; java.net.http client + requests
;; ===========================================================================

(defn new-http-client
  "Construct a JDK `java.net.http.HttpClient` with a 5s connect timeout.
  `HttpClient` instances are thread-safe and pool connections internally
  (JDK contract), so concurrency tests can share one across worker
  threads. The `.send` arity stays per-call so a caller picks the body
  handler (`ofString` / `ofInputStream`) it needs."
  []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn http-get-request
  "Build a GET `HttpRequest` for `http://127.0.0.1:<port><path>` with a
  per-request `timeout-secs` read timeout. `timeout-secs` is the
  required per-test knob, stated explicitly at the call site."
  ^HttpRequest [port path timeout-secs]
  (-> (HttpRequest/newBuilder)
      (.uri (URI/create (str "http://127.0.0.1:" port path)))
      (.timeout (Duration/ofSeconds timeout-secs))
      (.GET)
      (.build)))

(defn http-get
  "Issue a real HTTP GET against `http://127.0.0.1:<port><path>` and
  return the wire-observed result.

    (http-get client port path timeout-secs)
      → {:status int :body string}

    (http-get client port path timeout-secs :with-headers? true)
      → {:status int :headers {name [val ...]} :body string}

  `:headers` is java.net.http's natural `{name -> [val ...]}` multimap,
  preserved verbatim so a CRLF/header-noise scan reads every value
  exactly as it arrived. `timeout-secs` is the explicit per-call read
  timeout (see `http-get-request`)."
  [^HttpClient client port path timeout-secs & {:keys [with-headers?]}]
  (let [req  (http-get-request port path timeout-secs)
        resp (.send client req (HttpResponse$BodyHandlers/ofString))
        base {:status (.statusCode resp)
              :body   (.body resp)}]
    (if with-headers?
      (assoc base :headers (->> (.. resp (headers) (map))
                                (map (fn [[k v]] [k (vec v)]))
                                (into {})))
      base)))

;; ===========================================================================
;; Streaming-writer daemon-thread leak detector
;; ===========================================================================

(def daemon-thread-name-prefix
  "Name prefix the streaming writer (`stream-handler`) gives each
  per-request daemon thread (`rf2-ssr-streaming-<frame-id>`). Leak
  detection scopes by this prefix so it never counts unrelated threads."
  "rf2-ssr-streaming-")

(defn live-streaming-threads
  "Return the vector of currently-live Threads whose name begins with
  `daemon-thread-name-prefix` — i.e. streaming-writer daemon threads
  still alive. Empty when the no-leak teardown has settled."
  []
  (->> (Thread/getAllStackTraces)
       (.keySet)
       (filter (fn [^Thread t]
                 (and (.isAlive t)
                      (some-> (.getName t)
                              (.startsWith daemon-thread-name-prefix)))))
       vec))

(defn await-no-streaming-threads!
  "Poll until no `rf2-ssr-streaming-*` thread is alive, or `timeout-ms`
  elapses. RETURNS the final seq of live threads — empty on success,
  non-empty (the leaked threads) on timeout — so a caller's assertion
  can name the offenders in its failure message. Does NOT throw on
  timeout rather than throwing.

  `poll-ms` is explicit so each caller states its own cadence."
  [timeout-ms poll-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [alive (live-streaming-threads)]
        (cond
          (empty? alive)                           []
          (>= (System/currentTimeMillis) deadline) alive
          :else (do (Thread/sleep (long poll-ms)) (recur)))))))
