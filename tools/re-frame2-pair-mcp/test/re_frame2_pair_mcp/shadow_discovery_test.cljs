(ns re-frame2-pair-mcp.shadow-discovery-test
  "Unit tests for the shadow-cljs HTTP probe.

  Three surfaces under test, none of which opens a real socket:

    - `extract-project-home` — pure transit-json string → string|nil.
      Driven directly from synthetic JSON bodies; no HTTP.
    - `fetch-project-info` — the HTTP-edge fn. Its 3-arity takes an
      injected request-fn (a `(opts callback) -> ClientRequest`, mirroring
      Node's `http.request`), so a FAKE ClientRequest drives the
      below-the-socket branches directly: 200 single- and multi-chunk body
      assembly, a non-200 reject, a request `error`, the bounded-timeout
      `destroy` + reject, the response `error`, and the settle-once
      double-settle guard.
    - `discover-project-home*` — the fetch+parse composition. Here the
      whole `fetch-project-info` step is stubbed AWAY via the injected
      fetch-fn seam (it just returns a resolved/rejected Promise), so
      these tests pin the compose-and-nil-on-error contract only — NOT
      fetch-project-info's own edge handling (non-200 / connection
      refused / timeout are simulated by the stub, not exercised). That
      handling is covered by the `fetch-project-info` tests above.

  No test in this file talks to a live shadow server — the live probe
  is exercised by hand against `http://localhost:9630/api/project-info`
  during development and by the integration testbed at boot. CI runs
  fully offline."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame2-pair-mcp.shadow-discovery :as sd]))

;; ===========================================================================
;; extract-project-home — transit-json parser.
;; ===========================================================================

(deftest extract-project-home-pulls-canonical-payload
  (testing "the live shadow /api/project-info shape returns :project-home"
    ;; Verbatim from `curl http://localhost:9630/api/project-info` on a
    ;; running shadow-cljs instance — the contract under test.
    (let [body (str "[\"^ \","
                    "\"~:project-config\",\"C:\\\\Users\\\\me\\\\proj\\\\shadow-cljs.edn\","
                    "\"~:project-home\",\"C:\\\\Users\\\\me\\\\proj\","
                    "\"~:version\",\"3.4.10\"]")]
      (is (= "C:\\Users\\me\\proj" (sd/extract-project-home body))))))

(deftest extract-project-home-handles-posix-path
  (let [body (str "[\"^ \","
                  "\"~:project-config\",\"/home/me/proj/shadow-cljs.edn\","
                  "\"~:project-home\",\"/home/me/proj\","
                  "\"~:version\",\"3.4.10\"]")]
    (is (= "/home/me/proj" (sd/extract-project-home body)))))

(deftest extract-project-home-tolerates-key-reordering
  (testing "the parser walks key/value pairs — key order isn't load-bearing"
    (let [body (str "[\"^ \","
                    "\"~:version\",\"3.4.10\","
                    "\"~:project-home\",\"/x/y\","
                    "\"~:project-config\",\"/x/y/shadow-cljs.edn\"]")]
      (is (= "/x/y" (sd/extract-project-home body))))))

(deftest extract-project-home-missing-key-returns-nil
  (testing "a payload without :project-home returns nil, not throw"
    (let [body (str "[\"^ \","
                    "\"~:project-config\",\"/x/y/shadow-cljs.edn\","
                    "\"~:version\",\"3.4.10\"]")]
      (is (nil? (sd/extract-project-home body))))))

(deftest extract-project-home-non-string-value-returns-nil
  (testing "an integer / null at :project-home is rejected"
    (is (nil? (sd/extract-project-home
                "[\"^ \",\"~:project-home\",42]")))
    (is (nil? (sd/extract-project-home
                "[\"^ \",\"~:project-home\",null]")))))

(deftest extract-project-home-non-map-payload-returns-nil
  (testing "non-transit-map shapes (a bare object, a bare array, a string) → nil"
    (is (nil? (sd/extract-project-home "{\"project-home\":\"/x\"}"))
        "vanilla JSON object isn't the transit-map-as-array shape")
    (is (nil? (sd/extract-project-home "[1,2,3]"))
        "array without the \"^ \" sentinel isn't a transit map")
    (is (nil? (sd/extract-project-home "\"hello\""))
        "string body — no map shape at all")))

(deftest extract-project-home-malformed-json-returns-nil
  (testing "JSON parse failure must not throw — every error path collapses to nil"
    (is (nil? (sd/extract-project-home "not-json-at-all")))
    (is (nil? (sd/extract-project-home "")))
    (is (nil? (sd/extract-project-home "[\"^ \", \"~:project-home\""))
        "truncated array")))

;; ===========================================================================
;; fetch-project-info — the HTTP edge, driven through an injected request-fn.
;;
;; `fetch-project-info`'s 3-arity takes a request-fn matching Node's
;; `http.request` shape — `(opts callback) -> ClientRequest`, where `callback`
;; receives the IncomingMessage. `make-fake-transport` returns such a fn plus a
;; `state` atom the test drives: it records the response callback and the
;; ClientRequest's `on` / `setTimeout` / `destroy` / `end` calls, so the test
;; can fire a fake response (and its data/end/error events), a request error,
;; or the timeout, and assert how `fetch-project-info` settles. No socket.
;; ===========================================================================

(defn- fake-res
  "Minimal stand-in for Node's http IncomingMessage. `.statusCode` is fixed;
  `.on` records event handlers into `handlers`; `.setEncoding` / `.resume` are
  inert. The test fires the recorded data/end/error handlers to drive the body
  assembly."
  [status handlers]
  #js {:statusCode  status
       :setEncoding (fn [_enc] nil)
       :resume      (fn [] nil)
       :on          (fn [event cb] (swap! handlers assoc event cb) nil)})

(defn- make-fake-transport
  "Returns `[request-fn state]`. `request-fn` matches the seam
  `fetch-project-info` expects — `(opts callback) -> ClientRequest` — recording
  the response `callback` under `:res-cb` and returning a fake ClientRequest
  whose `on` / `setTimeout` / `destroy` / `end` calls land in `state`. Drive
  settlement by invoking `(:res-cb @state)` with a `fake-res`, then that res's
  recorded data/end/error handlers; or the recorded req `error` handler; or
  `(:timeout-cb @state)`."
  []
  (let [state (atom {:req-handlers {} :res-cb nil :timeout-cb nil
                     :destroyed nil :ended false :opts nil})
        req   #js {:on         (fn [event cb]
                                 (swap! state assoc-in [:req-handlers event] cb) nil)
                   :setTimeout (fn [_ms cb] (swap! state assoc :timeout-cb cb) nil)
                   :destroy    (fn [err] (swap! state assoc :destroyed err) nil)
                   :end        (fn [] (swap! state assoc :ended true) nil)}
        request-fn (fn [opts cb]
                     (swap! state assoc :opts opts :res-cb cb)
                     req)]
    [request-fn state]))

;; The `done`-calling `.then` chain is the LAST form of every `async` body
;; below (matching the convention the discover-project-home* tests use):
;; drive the fake req/res settlement FIRST, then attach `.then`. Attaching
;; `.then` and only THEN settling synchronously in the same body trips
;; cljs.test's run-block into a spurious "done called more than one time".

(deftest fetch-project-info-threads-opts-and-ends-the-request
  (testing "host + port reach the request opts and the request is .end()ed"
    (async done
      (let [[request-fn state] (make-fake-transport)
            res-handlers (atom {})
            p (sd/fetch-project-info "10.0.0.5" 9700 request-fn)]
        ((:res-cb @state) (fake-res 200 res-handlers))
        ((get @res-handlers "data") "{ok}")
        ((get @res-handlers "end"))
        (-> p
            (.then (fn [_]
                     (let [opts (:opts @state)]
                       (is (= "10.0.0.5" (.-host opts)))
                       (is (= 9700 (.-port opts)))
                       (is (= "/api/project-info" (.-path opts)))
                       (is (true? (:ended @state))
                           "req.end() fires the request"))
                     (done))))))))

(deftest fetch-project-info-200-single-chunk-resolves-body
  (testing "a 200 with one chunk resolves the body verbatim"
    (async done
      (let [[request-fn state] (make-fake-transport)
            res-handlers (atom {})
            p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
        ((:res-cb @state) (fake-res 200 res-handlers))
        ((get @res-handlers "data") "{body}")
        ((get @res-handlers "end"))
        (-> p
            (.then (fn [body]
                     (is (= "{body}" body))
                     (done))))))))

(deftest fetch-project-info-200-multi-chunk-assembles-body
  (testing "a chunked 200 body is concatenated in arrival order"
    (async done
      (let [[request-fn state] (make-fake-transport)
            res-handlers (atom {})
            p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
        ((:res-cb @state) (fake-res 200 res-handlers))
        ((get @res-handlers "data") "ab")
        ((get @res-handlers "data") "cd")
        ((get @res-handlers "data") "ef")
        ((get @res-handlers "end"))
        (-> p
            (.then (fn [body]
                     (is (= "abcdef" body)
                         "the data-event accumulator joins all chunks")
                     (done))))))))

(deftest fetch-project-info-non-200-rejects-with-status
  (testing "a non-200 response rejects, carrying the status code"
    (async done
      (let [[request-fn state] (make-fake-transport)
            res-handlers (atom {})
            p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
        ((:res-cb @state) (fake-res 404 res-handlers))
        (-> p
            (.then (fn [_]
                     (is false "a non-200 must reject, not resolve")
                     (done))
                   (fn [err]
                     (is (re-find #"HTTP 404" (.-message err))
                         "reject message names the status code")
                     (done))))))))

(deftest fetch-project-info-request-error-rejects
  (testing "a ClientRequest 'error' (e.g. connection refused) rejects"
    (async done
      (let [[request-fn state] (make-fake-transport)
            p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
        ((get-in @state [:req-handlers "error"]) (js/Error. "ECONNREFUSED"))
        (-> p
            (.then (fn [_]
                     (is false "a request error must reject")
                     (done))
                   (fn [err]
                     (is (= "ECONNREFUSED" (.-message err)))
                     (done))))))))

(deftest fetch-project-info-timeout-destroys-and-rejects
  (testing "the bounded-probe timeout destroys the request and rejects"
    (async done
      (let [[request-fn state] (make-fake-transport)
            p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
        ((:timeout-cb @state))
        (-> p
            (.then (fn [_]
                     (is false "a timed-out probe must reject")
                     (done))
                   (fn [err]
                     (is (re-find #"timed out" (.-message err)))
                     (is (some? (:destroyed @state))
                         "req.destroy tears the socket down before rejecting")
                     (done))))))))

(deftest fetch-project-info-response-error-rejects
  (testing "a mid-body response 'error' event rejects the probe"
    (async done
      (let [[request-fn state] (make-fake-transport)
            res-handlers (atom {})
            p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
        ((:res-cb @state) (fake-res 200 res-handlers))
        ((get @res-handlers "data") "partial")
        ((get @res-handlers "error") (js/Error. "socket hang up"))
        (-> p
            (.then (fn [_]
                     (is false "a response error must reject")
                     (done))
                   (fn [err]
                     (is (= "socket hang up" (.-message err)))
                     (done))))))))

(deftest fetch-project-info-double-settle-guard-holds
  (testing "once end has resolved, a late timeout is swallowed (settle-once)"
    (async done
      (let [[request-fn state] (make-fake-transport)
            res-handlers (atom {})
            p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
        ((:res-cb @state) (fake-res 200 res-handlers))
        ((get @res-handlers "data") "done-body")
        ((get @res-handlers "end"))
        ;; A late timeout fires AFTER the resolve — compare-and-set! swallows it.
        ((:timeout-cb @state))
        (-> p
            (.then (fn [body]
                     (is (= "done-body" body)
                         "the FIRST settlement (resolve on end) wins")
                     (done))
                   (fn [_]
                     (is false "a resolved probe must not later reject")
                     (done))))))))

;; ===========================================================================
;; discover-project-home* — fetch + parse composition.
;;
;; The HTTP-fetch fn is injected (see `discover-project-home*`); tests
;; pass stubs. The fn under test never opens a socket in these tests;
;; the real HTTP path is covered manually via the live-nrepl integration
;; test and by the boot smoke (start the server with shadow up, see
;; "nREPL port =" log line).
;; ===========================================================================

(deftest discover-project-home-success-returns-path
  (async done
    (let [stub-fetch (fn [_host _port]
                       (js/Promise.resolve
                         "[\"^ \",\"~:project-home\",\"/abs/proj\"]"))]
      (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
          (.then (fn [v]
                   (is (= "/abs/proj" v))
                   (done)))))))

(deftest discover-project-home-fetch-rejection-yields-nil
  (testing "shadow unreachable / non-200 / timeout — every reject path → nil"
    (async done
      (let [stub-fetch (fn [_host _port]
                         (js/Promise.reject (js/Error. "ECONNREFUSED")))]
        (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
            (.then (fn [v]
                     (is (nil? v)
                         "rejection must surface as nil so the cascade falls through")
                     (done))))))))

(deftest discover-project-home-malformed-payload-yields-nil
  (testing "fetch succeeded but the body wasn't the transit-map shape"
    (async done
      (let [stub-fetch (fn [_host _port]
                         (js/Promise.resolve "not-the-shape-we-want"))]
        (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
            (.then (fn [v]
                     (is (nil? v)
                         "extract-project-home returned nil; cascade falls through")
                     (done))))))))

(deftest discover-project-home-missing-key-yields-nil
  (testing "fetch succeeded, transit-shape valid, but :project-home absent"
    (async done
      (let [stub-fetch (fn [_host _port]
                         (js/Promise.resolve
                           "[\"^ \",\"~:version\",\"3.4.10\"]"))]
        (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
            (.then (fn [v]
                     (is (nil? v))
                     (done))))))))

(deftest discover-project-home-args-thread-through
  (testing "host + port supplied to the wrapper reach the fetch-fn"
    (async done
      (let [seen-host (atom nil)
            seen-port (atom nil)
            stub-fetch (fn [host port]
                         (reset! seen-host host)
                         (reset! seen-port port)
                         (js/Promise.resolve
                           "[\"^ \",\"~:project-home\",\"/x\"]"))]
        (-> (sd/discover-project-home* "10.0.0.5" 9700 stub-fetch)
            (.then (fn [_]
                     (is (= "10.0.0.5" @seen-host))
                     (is (= 9700 @seen-port))
                     (done))))))))
