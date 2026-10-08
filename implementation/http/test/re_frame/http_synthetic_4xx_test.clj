(ns re-frame.http-synthetic-4xx-test
  "The `:else` arm of `handle-response!` end to end: a non-2xx status that is
  neither 4xx nor 5xx (a 1xx, or a 3xx the runtime did not follow) is
  classified as a synthetic `:rf.http/http-4xx` carrying the raw body, and
  routes through `maybe-retry!` so a `:retry {:on #{:rf.http/http-4xx}}` caller
  retries it like a real 4xx.

  `:redirect :error` selects the JDK `NEVER` redirect client, so a 302 from a
  loopback server surfaces unfollowed. Only a real 3xx driven through the
  cascade catches the arm being routed to `finalise-failure!` or reclassified."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private redirect-body "moved, and not followed")

(defn- start-counting-302-server! []
  (let [hits   (AtomicInteger. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (.incrementAndGet hits)
                        (let [^HttpExchange ex ex
                              bs (.getBytes redirect-body "UTF-8")]
                          (try
                            (.add (.getResponseHeaders ex) "Location" "/elsewhere")
                            (.sendResponseHeaders ex 302 (long (count bs)))
                            (with-open [os (.getResponseBody ex)]
                              (.write os bs))
                            (catch Throwable _ nil))))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server)) :hits hits}))

(defn- await-condition! [pred]
  (rf.test-support/poll-until pred {:timeout-ms 5000 :interval-ms 10
                                    :label "http-synthetic-4xx condition"}))

(deftest unfollowed-3xx-classifies-synthetic-4xx-and-retries
  (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-302-server!)
        replies (atom [])]
    (try
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      (rf/reg-event :issue
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url      (str "http://127.0.0.1:" (:port srv) "/")
                               :redirect :error}
                  :decode     :json
                  :retry      {:on           #{:rf.http/http-4xx}
                               :max-attempts 2
                               :backoff      {:base-ms 100 :factor 1 :max-ms 100}}
                  :request-id :synth
                  :on-failure [:reply/recorder]
                  :on-success [:reply/recorder]}]]}))
      (rf/dispatch-sync [:issue])
      ;; Two hits: the attempt and one retry. A direct finalise never retries.
      (await-condition! #(= 2 (.get hits)))
      (await-condition! #(seq @replies))
      (is (= [{:status :error :error {:kind :rf.http/http-4xx :status 302 :body redirect-body}}]
             (mapv #(-> (select-keys % [:status :error])
                        (update :error select-keys [:kind :status :body]))
                   @replies))
          "one final reply, carrying the raw 3xx status and body")
      (is (empty? (rf.http.registry/in-flight-snapshot)))
      (finally
        (.stop ^HttpServer (:server srv) 0)))))
