(ns re-frame.ssr.ring.node-body-deadline-test
  "rf2-fzbj.24 — THE DERIVED HTTP DEADLINE BOUNDS THE WHOLE EXCHANGE,
  INCLUDING THE BODY.

  `re-frame.ssr.ring.node/renderer` derives one explicit HTTP budget per
  request (`http-timeout-ms` — `:timeout-ms` + `:admission-ms` +
  `wire-margin-ms`, S6) and an operator sizes a deployment by it. The JDK's
  own `HttpRequest.Builder.timeout` bounds the phase BEFORE the response
  headers arrive and nothing after it, so a peer that answers 200 promptly
  and then stalls mid-body held the synchronous Ring request — and its
  request frame — open indefinitely, and eventually returned SUCCESS past
  the stated budget.

  The existing deadline coverage cannot see this. `node-crossing-test`'s
  `(d)` arm proves the SIDECAR's 504 arrives first (`:observed-by
  :sidecar`); a healthy in-process fake returns a completed body. Both need
  the answer to complete.

  This namespace is untagged — it runs in the default `:test` lane
  (`jvm-ssr-ring`) and spawns no Node. The peer is a JDK `HttpServer` on a
  port-0 loopback socket that sends the expected build header and the first
  of two promised body bytes, then WITHHOLDS the second until the test
  releases it (with a finite backstop, so a regression stalls the row
  rather than the run).

  Three things are asserted, and the first is the defect:

    1. the handler returns the projected, fail-closed 5xx WELL INSIDE the
       backstop, carrying `:rf.error/ssr-node-deadline` / `:observed-by
       :jvm` / the derived `:http-timeout-ms`, with no partial Node markup
       and no hydration payload, and the request frame gone;
    2. releasing the withheld byte afterwards produces NO second response
       and revives nothing — the exchange was cancelled, not parked;
    3. the SAME renderer serves the next request normally, so cancelling
       one exchange does not poison the shared `HttpClient`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support])
  (:import [java.io IOException OutputStream]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

;; ===========================================================================
;; The peer: 200, the right build, one byte now and one byte later
;; ===========================================================================

(def ^:private build-id "body-deadline-build-1")

(def ^:private backstop-ms
  "How long the peer withholds its second body byte before giving up and
  sending it anyway. It exists so the UNREPAIRED code fails this row
  instead of hanging the lane; every assertion below is sized well under
  it, so a pass can never be the backstop firing."
  4000)

(defn- with-stalling-peer
  "Run `(f url release-latch)` against a loopback `HttpServer` whose
  `/render` answers HTTP 200 with `x-rf-ssr-build` and a promised body of
  two bytes. The FIRST request writes `A`, flushes it, and then waits on
  the returned latch (or `backstop-ms`) before writing `B`. Every later
  request answers `AB` at once, so a caller can prove the renderer still
  works after a cancelled exchange.

  The server keeps the JDK's default dispatcher — one handler at a time —
  so the caller counts the latch down before issuing its second request."
  [f]
  (let [release  (CountDownLatch. 1)
        seen     (atom 0)
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
      server "/render"
      (proxy [HttpHandler] []
        (handle [^HttpExchange ex]
          (try
            (.add (.getResponseHeaders ex) "x-rf-ssr-build" build-id)
            (.sendResponseHeaders ex 200 2)
            (let [^OutputStream out (.getResponseBody ex)
                  stall?            (= 1 (swap! seen inc))]
              (.write out (int 65))                       ; A
              (.flush out)
              (when stall?
                (.await release backstop-ms TimeUnit/MILLISECONDS))
              (.write out (int 66))                       ; B
              (.flush out))
            ;; A cancelled exchange can make the late write fail. Whether it
            ;; does is the OS's business — a write into a closed socket's
            ;; send buffer usually succeeds — so this is swallowed rather
            ;; than asserted on.
            (catch IOException _ nil)
            (finally (.close ex)))
          nil)))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) release)
      (finally
        (.countDown release)
        (.stop server 0)))))

;; ===========================================================================
;; The app, the handler, and the error stream
;; ===========================================================================

(def ^:private request {:uri "/body-deadline" :request-method :get :headers {}})

(defn- register-app! []
  (rf/reg-event :rf.test.body-deadline/init
    {:platforms #{:server}}
    (fn [_ _] {:db {:heading "Body deadline"}})))

(def ^:private render-timeout-ms 50)

(def ^:private derived-http-timeout-ms
  "The budget the adapter advertises for this renderer, read from the
  adapter's own pure derivation rather than restated as a literal."
  (rf.ssr.ring.node/http-timeout-ms {:timeout-ms   render-timeout-ms
                                     :admission-ms 0}))

(defn- handler [url]
  (rf.ssr.ring/ssr-handler
    {:initial-events [[:rf.test.body-deadline/init]]
     :payload        [:heading]
     :renderer       (rf.ssr.ring.node/renderer
                       {:endpoint     url
                        :entry        "app/root"
                        :build-id     build-id
                        :timeout-ms   render-timeout-ms
                        :admission-ms 0
                        :render-state {:app-db [:heading]}})
     :error-view     (fn [{:keys [code]}]
                       [:main#body-deadline-error [:p (str "projected:" (name code))]])}))

(defn- capture-errors! []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::body-deadline-recorder
                                            (fn [record] (swap! seen conj record)))
    seen))

(defn- render-failure-data [records]
  (->> records
       (filter #(= :rf.error/ssr-render-failed (:error %)))
       (map #(-> % :exception ex-data))
       first))

;; ===========================================================================
;; The row
;; ===========================================================================

(deftest a-peer-that-stalls-mid-body-hits-the-derived-deadline-and-frees-the-frame
  (register-app!)
  (with-stalling-peer
    (fn [url release]
      (let [seen          (capture-errors!)
            frames-before (set (rf/frame-ids))
            h             (handler url)
            t0            (System/nanoTime)
            {:keys [status body]} (h request)
            elapsed-ms    (/ (- (System/nanoTime) t0) 1e6)]

        (testing "the derived HTTP budget bounds COMPLETE body consumption,
                  not just the wait for headers"
          (is (< elapsed-ms 2500.0)
              (format (str "the handler returned in %.1f ms; the budget is %d ms "
                           "and the peer's backstop is %d ms, so anything near "
                           "the backstop means the body read was unbounded")
                      elapsed-ms derived-http-timeout-ms backstop-ms))
          (is (= 500 status) "projected, fail-closed — never a late success")
          (is (str/includes? body "<main id=\"body-deadline-error\">")
              "the :error-view rendered")
          (is (not (str/includes? body "__rf_payload"))
              "no hydration payload on the error arm")
          (is (not (str/includes? body "AB"))
              "no partial or completed Node markup escapes"))

        (testing "it is the JVM's own deadline, with the derived budget in ex-data"
          (let [{:keys [observed-by timeout-ms http-timeout-ms] :as data}
                (render-failure-data @seen)]
            (is (= :rf.error/ssr-node-deadline (:rf.error/id data)))
            (is (= :jvm observed-by)
                "the JVM observed it — the peer never sent a 504")
            (is (= render-timeout-ms timeout-ms))
            (is (= derived-http-timeout-ms http-timeout-ms))))

        (testing "the request frame is gone — a stalled peer cannot retain
                  per-request state"
          (is (= frames-before (set (rf/frame-ids)))))

        (testing "releasing the withheld byte revives nothing, and the SAME
                  renderer still serves the next request"
          (.countDown release)
          (let [{:keys [status body]} (h request)]
            (is (= 200 status) "the shared HttpClient survived the cancellation")
            (is (str/includes? body "AB") "…and this answer's body crossed whole")))

        (rf.error-emit/unregister-error-listener! ::body-deadline-recorder)))))
