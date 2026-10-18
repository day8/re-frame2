(ns re-frame.http-stub-transport-test
  "The stub transport cases that need a clock, on the JVM: a retry the real
  policy re-sends through the stub, the request's `:timeout-ms` bounding a
  held exchange, a route's delay, a binary body, and the two network failures
  a stub may report. The host-symmetric cases live in
  `re-frame.http-stub-transport-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.stub-transport :as stub]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private url "https://api.example.test/items")

(defn- fire!
  "Issue `args` with its reply addressed to app-db's `::reply`."
  [args]
  (rf/reg-event ::replied
    {:doc "Records the reply."}
    (fn [{:keys [db]} [_ reply]] {:db (assoc db ::reply reply)}))
  (rf/reg-event ::fire
    {:doc "Issues the request under test."}
    (fn [_ _] {:fx [[:rf.http/managed (assoc args :reply-to [::replied])]]}))
  (rf/dispatch-sync [::fire]))

(defn- reply [] (::reply (rf/app-db-value :rf/default)))

(defn- await-reply! []
  (rf.test-support/poll-until reply {:timeout-ms 8000 :label "the reply lands"}))

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
        transport (stub/route-transport
                    {[:get url] (fn [_] (if (= 1 (swap! hits inc))
                                          {:status 503 :body "busy"}
                                          {:body {:n 2}}))})
        traces    (capturing
                    #(do (stub/with-transport transport
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
  (let [{:keys [transport exchanges]} (stub/pending-transport)]
    (stub/with-transport transport #(fire! {:request {:url url} :timeout-ms 30}))
    (await-reply!)
    (let [{:keys [respond! cancelled?]} (first @exchanges)]
      (is (= [:error :rf.http/timeout 30]
             ((juxt :status #(get-in % [:error :kind]) #(get-in % [:error :limit-ms])) (reply))))
      (is (true? (cancelled?)) "the timeout cancelled the stub's exchange")
      (is (false? (respond! {:body {:too :late}})) "and fences its late answer")
      (is (empty? (rf.http.managed/in-flight-snapshot))))))

(deftest a-routes-delay-defers-the-answer
  (stub/with-transport (stub/route-transport {[:get url] {:body {:n 1} :delay-ms 40}})
    #(fire! {:request {:url url} :decode :json}))
  (is (nil? (reply)) "not inside the dispatch-sync drain")
  (await-reply!)
  (is (= {:n 1} (:value (reply)))))

(deftest a-binary-body-reaches-a-binary-decode
  (stub/with-transport
    (stub/route-transport {[:get url] {:headers     {"Content-Type" "application/octet-stream"}
                                       :body-binary (byte-array [1 2 3])}})
    #(fire! {:request {:url url} :decode :array-buffer}))
  (is (= [1 2 3] (vec (:value (reply))))))

(deftest a-stub-fails-a-request-only-as-a-network-failure
  (let [{:keys [transport exchanges]} (stub/pending-transport)]
    (stub/with-transport transport
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
