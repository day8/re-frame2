(ns re-frame.http-stub-transport-test
  "The stub transport cases that need a clock or a server, on the JVM: a retry
  the real policy re-sends through the stub, the request's `:timeout-ms`
  bounding a held exchange, a route's delay, a binary body, the two network
  failures a stub may report, and a `:rf.http/stub-only?` frame that never
  reaches the host. The host-symmetric cases live in
  `re-frame.http-stub-transport-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private url "https://api.example.test/items")

(defn- fire!
  "Issue `args` from `frame-id` (default `:rf/default`) with its reply
  addressed to that frame's app-db `::reply`."
  ([args] (fire! :rf/default args))
  ([frame-id args]
   (rf/reg-event ::replied
     {:doc "Records the reply."}
     (fn [{:keys [db]} [_ reply]] {:db (assoc db ::reply reply)}))
   (rf/reg-event ::fire
     {:doc "Issues the request under test."}
     (fn [_ _] {:fx [[:rf.http/managed (assoc args :reply-to [::replied])]]}))
   (rf/dispatch-sync [::fire] {:frame frame-id})))

(defn- reply
  ([] (reply :rf/default))
  ([frame-id] (::reply (rf/app-db-value frame-id))))

(defn- await-reply!
  ([] (await-reply! :rf/default))
  ([frame-id]
   (rf.test-support/poll-until #(reply frame-id) {:timeout-ms 8000 :label "the reply lands"})))

(defn- with-counting-server
  "Run `(f base-url hits)` against an in-process server that answers 204 and
  counts the requests it receives in the atom `hits`."
  [f]
  (let [hits   (atom 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setHandler (.createContext server "/")
                 (reify HttpHandler
                   (handle [_ exchange]
                     (swap! hits inc)
                     (.sendResponseHeaders ^HttpExchange exchange 204 -1)
                     (.close ^HttpExchange exchange))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) hits)
         (finally (.stop server 0)))))

(defn- capturing
  "Run `(f)` with every trace event collected; return the events."
  [f]
  (let [traces (atom [])
        id     (keyword (gensym "stub-transport-jvm"))]
    (rf.trace.tooling/register-listener! id #(swap! traces conj %))
    (try (f) @traces
         (finally (rf.trace.tooling/unregister-listener! id)))))

(deftest a-retry-is-re-sent-through-the-stub
  (let [hits      (atom 0)
        transport (rf.http.stub-transport/route-transport
                    {[:get url] (fn [_] (if (= 1 (swap! hits inc))
                                          {:status 503 :body "busy"}
                                          {:body {:n 2}}))})
        traces    (capturing
                    #(do (rf.http.stub-transport/with-transport transport
                           (fn [] (fire! {:request {:url url}
                                          :decode  :json
                                          :retry   {:on           #{:rf.http/http-5xx}
                                                    :max-attempts 2
                                                    :backoff      {:base-ms 1 :factor 1 :max-ms 1}}})))
                         (await-reply!)))]
    (is (= 2 @hits) "the real retry policy sent the request twice")
    (is (= [:ok {:n 2} 2] ((juxt :status :value :attempt) (reply))))
    (is (= [[1 :retried]]
           (mapv (juxt #(get-in % [:tags :attempt]) :recovery)
                 (filter #(= :rf.http/retry-attempt (:operation %)) traces)))
        "the retry is counted on the trace")))

(deftest the-requests-timeout-bounds-a-held-exchange
  (let [{:keys [transport exchanges]} (rf.http.stub-transport/pending-transport)]
    (rf.http.stub-transport/with-transport transport #(fire! {:request {:url url} :timeout-ms 30}))
    (await-reply!)
    (let [{:keys [respond! cancelled?]} (first @exchanges)]
      (is (= [:error :rf.http/timeout 30]
             ((juxt :status #(get-in % [:error :kind]) #(get-in % [:error :limit-ms])) (reply))))
      (is (true? (cancelled?)) "the timeout cancelled the stub's exchange")
      (is (false? (respond! {:body {:too :late}})) "and fences its late answer")
      (is (empty? (rf.http.managed/in-flight-snapshot))))))

(deftest a-routes-delay-defers-the-answer
  (rf.http.stub-transport/with-transport
    (rf.http.stub-transport/route-transport {[:get url] {:body {:n 1} :delay-ms 40}})
    #(fire! {:request {:url url} :decode :json}))
  (is (nil? (reply)) "not inside the dispatch-sync drain")
  (await-reply!)
  (is (= {:n 1} (:value (reply)))))

(deftest a-binary-body-reaches-a-binary-decode
  (rf.http.stub-transport/with-transport
    (rf.http.stub-transport/route-transport
      {[:get url] {:headers     {"Content-Type" "application/octet-stream"}
                   :body-binary (byte-array [1 2 3])}})
    #(fire! {:request {:url url} :decode :array-buffer}))
  (is (= [1 2 3] (vec (:value (reply))))))

(deftest a-stub-fails-a-request-only-as-a-network-failure
  (let [{:keys [transport exchanges]} (rf.http.stub-transport/pending-transport)]
    (rf.http.stub-transport/with-transport transport
      #(dotimes [_ 2] (fire! {:request {:url url} :timeout-ms 5000})))
    (let [[first-x second-x] @exchanges]
      (testing "a 5xx is a response, never a failure a transport reports"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"respond"
                              ((:fail! first-x) {:kind :rf.http/http-5xx}))))
      (testing "a timeout carries the request's limit"
        (is (true? ((:fail! first-x) {:kind :rf.http/timeout})))
        (await-reply!)
        (is (= [:rf.http/timeout 5000] ((juxt :kind :limit-ms) (:error (reply))))))
      (testing "a transport failure is the default kind"
        (rf/dispatch-sync [::replied nil])
        (is (true? ((:fail! second-x) {:message "connection reset"})))
        (await-reply!)
        (is (= [:rf.http/transport "connection reset"] ((juxt :kind :message) (:error (reply)))))))))

(deftest a-stub-only-frame-never-reaches-the-host
  (with-counting-server
    (fn [base hits]
      (rf/make-frame {:id ::under-test :preset :test})
      (rf/make-frame {:id ::live})
      (testing "a :test-preset frame with no transport in place fails the
                request as :rf.http/transport, naming the remedy, and the host
                adapter never sends it"
        (fire! ::under-test {:request {:url (str base "/x")}})
        (let [error (:error (reply ::under-test))]
          (is (= [:error :rf.http/transport]
                 [(:status (reply ::under-test)) (:kind error)]))
          (is (re-find #"with-transport" (str (:message error)))))
        (is (zero? @hits)))
      (testing "a transport in place still answers the preset frame"
        (rf.http.stub-transport/with-transport
          (rf.http.stub-transport/route-transport {[:get (str base "/x")] {:body {:n 1}}})
          #(fire! ::under-test {:request {:url (str base "/x")} :decode :json}))
        (is (= [:ok {:n 1}] ((juxt :status :value) (reply ::under-test)))))
      (testing "control: the same request from a frame with no preset reaches
                the server"
        (fire! ::live {:request {:url (str base "/x")}})
        (await-reply! ::live)
        (is (= :ok (:status (reply ::live))))
        (is (= 1 @hits))))))
