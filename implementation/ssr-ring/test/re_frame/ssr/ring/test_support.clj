(ns re-frame.ssr.ring.test-support
  "Shared JVM test scaffolding for the ssr-ring suite (rf2-l1qgjw issue 3).

  Five live-host / streaming-thread test namespaces had grown
  near-identical copies of: an ephemeral-port Jetty start/stop +
  `with-jetty` macro, a `java.net.http` client + request builder + two
  `http-get` result shapes, and a `rf2-ssr-streaming-*` daemon-thread
  leak detector. Small drift had already crept in (request timeouts of
  10s vs 30s, poll cadences of 10ms vs 50ms). This namespace single-
  sources the MECHANISM; every intentional per-test difference (request
  timeout, leak-poll cadence) stays an explicit PARAMETER so a caller
  reads its own knobs at the call site rather than hiding them in a copy
  edit.

  This is the established local pattern: the artefact already shares
  `re-frame.ssr.test-fixture` for the per-test reset (deps.edn
  `:extra-paths`), and `with-jetty` etc. are pure test plumbing with no
  production-path coupling — so consolidating them here removes review
  surface without introducing a new abstraction style.

  Independence note: requiring this ns does NOT co-load any test
  namespace (it depends only on `ring.adapter.jetty` + JDK classes), so
  every test ns stays independently runnable under the artefact `:test`
  alias.

  Contract smoke coverage for these helpers lives in
  `test_support_contract_test.clj` (rf2-l1qgjw)."
  (:require [ring.adapter.jetty :as jetty])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [org.eclipse.jetty.server Server]))

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
  load-bearing per-test knob (the old copies drifted 10s vs 30s), so it
  is required — the caller states it explicitly at the call site."
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
  timeout (rf2-fun38: a deliberately different contract from a throwing
  `poll-until`).

  `poll-ms` is the explicit poll cadence (the old copies used 10ms for
  single-request tests and 50ms for the higher-peak concurrency burst;
  keep it a parameter so each caller states its own cadence)."
  [timeout-ms poll-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [alive (live-streaming-threads)]
        (cond
          (empty? alive)                           []
          (>= (System/currentTimeMillis) deadline) alive
          :else (do (Thread/sleep (long poll-ms)) (recur)))))))
