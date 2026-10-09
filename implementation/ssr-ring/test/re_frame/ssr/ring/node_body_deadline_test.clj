(ns re-frame.ssr.ring.node-body-deadline-test
  "The Node renderer's derived HTTP budget bounds the WHOLE exchange, body
  included. `HttpRequest.Builder.timeout` bounds only the wait for headers, so
  a peer that answers 200 promptly and then stalls mid-body would otherwise
  hold the Ring request, and its frame, open indefinitely. No Node: the peer
  is an in-process JDK `HttpServer`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]
            [re-frame.test-support :refer [with-emit-recorder!]])
  (:import [java.io IOException OutputStream]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private build-id "body-deadline-build-1")

(def ^:private backstop-ms
  "How long the peer withholds its second byte, so a body-blind timeout fails
  the row instead of hanging the lane."
  4000)

(defn- with-stalling-peer
  "Run `(f url release-latch)` against a peer whose `/render` answers 200 with
  a promised two-byte body. The FIRST request writes `A`, then waits on the
  latch (or the backstop) before `B`; later requests answer `AB` at once."
  [f]
  (let [release (CountDownLatch. 1)
        seen    (atom 0)
        server  (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
      server "/render"
      (proxy [HttpHandler] []
        (handle [^HttpExchange ex]
          (try
            (.add (.getResponseHeaders ex) "x-rf-ssr-build" build-id)
            (.sendResponseHeaders ex 200 2)
            (let [^OutputStream out (.getResponseBody ex)]
              (.write out (int 65))
              (.flush out)
              (when (= 1 (swap! seen inc))
                (.await release backstop-ms TimeUnit/MILLISECONDS))
              (.write out (int 66))
              (.flush out))
            ;; A write into a cancelled exchange may or may not fail; not asserted.
            (catch IOException _ nil)
            (finally (.close ex)))
          nil)))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) release)
      (finally
        (.countDown release)
        (.stop server 0)))))

(deftest a-peer-that-stalls-mid-body-hits-the-derived-deadline-and-frees-the-frame
  (rf/reg-event :rf.test.body-deadline/init {:platforms #{:server}}
    (fn [_ _] {:db {:heading "Body deadline"}}))
  (with-stalling-peer
    (fn [url release]
      (let [h (rf.ssr.ring/ssr-handler
                {:initial-events [[:rf.test.body-deadline/init]]
                 :payload        [:heading]
                 :renderer       (rf.ssr.ring.node/renderer
                                   {:endpoint     url
                                    :entry        "app/root"
                                    :build-id     build-id
                                    :timeout-ms   50
                                    :admission-ms 0
                                    :render-state {:app-db [:heading]}})
                 :error-view     (fn [_] [:main#body-deadline-error])})]
        (with-emit-recorder! [errs {:pred #(= :rf.error/ssr-render-failed (:error %))}]
          (let [t0         (System/nanoTime)
                {:keys [status body]} (h {:uri "/body-deadline" :request-method :get})
                elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
            ;; The budget is 550 ms; anything near the backstop means the body
            ;; read was unbounded.
            (is (< elapsed-ms 2500.0) (str "returned in " elapsed-ms " ms"))
            (is (= 500 status))
            (is (str/includes? body "<main id=\"body-deadline-error\">"))
            (is (= {:rf.error/id     :rf.error/ssr-node-deadline
                    :observed-by     :jvm
                    :timeout-ms      50
                    :http-timeout-ms 550}
                   (-> @errs first :exception ex-data
                       (select-keys [:rf.error/id :observed-by :timeout-ms :http-timeout-ms]))))))
        ;; Releasing the withheld byte revives nothing, and the cancelled
        ;; exchange did not poison the renderer's shared HttpClient.
        (.countDown release)
        (let [{:keys [status body]} (h {:uri "/body-deadline" :request-method :get})]
          (is (= 200 status))
          (is (str/includes? body "AB")))))))
