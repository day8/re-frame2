(ns re-frame.http-reply-lowering-test
  "Managed HTTP lowered onto the uniform reply envelope
  (`spec/Managed-Effects.md` §The uniform reply envelope), end to end through
  the real `java.net.http.HttpClient` transport against a loopback server:
  canonical success and failure replies, self-identifying failures,
  supersession, and the completion time a reply handler receives. The pure
  builders are pinned host-symmetrically in `http-reply-lowering-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.reply :as rf.http.reply]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.reply :as rf.reply]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-server! [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx (reify HttpHandler (handle [_ ex] (handler ex))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}] (.stop server 0))

(defn- write-response! [^HttpExchange exchange status content-type body]
  (let [bytes (.getBytes (str body) "UTF-8")]
    (when content-type
      (-> exchange .getResponseHeaders (.set "Content-Type" content-type)))
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- await-reply! [pred]
  (rf.test-support/poll-until
    #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
    {:timeout-ms 5000 :label "http-reply-lowering"}))

(defn- start-held-server!
  "A server that holds every exchange until `release` counts down, then
  answers `200 {\"v\":1}`. Holding #1 is what guarantees it is still in flight
  when #2 supersedes it; over loopback an unheld #1 can finish between two
  dispatch-syncs. The write is guarded because the superseded exchange's
  connection is already cancelled."
  [^java.util.concurrent.CountDownLatch release]
  (start-server!
    (fn [^HttpExchange ex]
      (try (.await release 30 java.util.concurrent.TimeUnit/SECONDS)
           (catch InterruptedException _ nil))
      (try (write-response! ex 200 "application/json" "{\"v\":1}")
           (catch java.io.IOException _ nil)))))

(def ^:private base-ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main
   :completed-at 1781078400456})

(deftest success-reply-response-meta-is-optional-and-canonical
  (let [meta* {:status      200
               :status-text "OK"
               :headers     {"content-type" "application/json"
                             "set-cookie"   ["a=1; Path=/" "b=2; Path=/"]}}
        r     (rf.http.reply/success-reply base-ctx {:title "Welcome"} meta*)]
    (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
    (is (= {:status :ok :value {:title "Welcome"} :meta meta*} (select-keys r [:status :value :meta])))
    (testing "absent metadata is omitted, never fabricated"
      (is (not-any? #(contains? % :meta) [(rf.http.reply/success-reply base-ctx {:v 1})
                                          (rf.http.reply/success-reply base-ctx {:v 1} nil)])))))

(deftest real-transport-success-reply-carries-response-meta
  (let [srv (start-server!
              (fn [^HttpExchange ex]
                (let [hs (.getResponseHeaders ex)]
                  (.set hs "X-Request-Cost" "3")
                  (.add hs "Set-Cookie" "session=abc; Path=/")
                  (.add hs "Set-Cookie" "csrf=xyz; Path=/"))
                (write-response! ex 200 "application/json" "{\"title\":\"hello\"}")))]
    (try
      (rf/reg-event :meta/load
        (fn [{:keys [db]} [_ msg reply]]
          (if reply
            {:db (assoc db :reply reply)}
            {:fx [[:rf.http/managed
                   {:request  {:url (str "http://127.0.0.1:" (:port srv) "/m")}
                    :decode   :json
                    :reply-to [:meta/load msg]}]]})))
      (rf/dispatch-sync [:meta/load {}])
      (let [reply (:reply (await-reply! #(some? (:reply %))))]
        (is (rf.reply/valid-reply? reply) (str (rf.reply/validate-reply reply)))
        (is (= [:ok :completed :http {:title "hello"}]
               ((juxt :status :rf.reply/work-status :rf.reply/work-kind :value) reply)))
        (is (= {:status 200 :headers {"x-request-cost" "3"
                                      "set-cookie"     ["session=abc; Path=/" "csrf=xyz; Path=/"]}}
               (-> (:meta reply)
                   (select-keys [:status :headers])
                   (update :headers select-keys ["x-request-cost" "set-cookie"])))
            "headers ride lower-cased; a multi-valued header is one vector of verbatim lines")
        (is (string? (get-in reply [:meta :status-text]))))
      (finally (stop-server! srv)))))

(defn- fetch-failure-reply
  "Issue one managed GET against `url` with an :on-failure target, drive it,
  and return the delivered failure reply."
  [url {:keys [request-id max-attempts]}]
  (rf/reg-event :sid/call
    (fn [_ _]
      {:fx [[:rf.http/managed
             (cond-> {:request    {:method :get :url url}
                      :decode     :json
                      :on-failure [:sid/failed]}
               request-id   (assoc :request-id request-id)
               max-attempts (assoc :retry {:on #{:rf.http/http-5xx :rf.http/transport}
                                           :max-attempts max-attempts
                                           :backoff {:base-ms 1 :factor 1 :max-ms 1}}))]]}))
  (rf/reg-event :sid/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :reply reply)}))
  (rf/dispatch-sync [:sid/call])
  (:reply (await-reply! #(some? (:reply %)))))

(def ^:private identity-keys [:kind :request :request-id :attempt :max-attempts :work/id :status])

(deftest failure-reply-is-self-identifying-4xx
  (let [srv (start-server! (fn [^HttpExchange ex] (write-response! ex 404 "text/plain" "nope")))]
    (try
      (let [url   (str "http://127.0.0.1:" (:port srv) "/gone")
            reply (fetch-failure-reply url {:request-id :sid/get})]
        (is (= {:status :error :rf.reply/work-status :failed}
               (select-keys reply [:status :rf.reply/work-status :meta]))
            "a failure reply carries no :meta; its wire facts ride :error")
        (is (= {:kind :rf.http/http-4xx :request {:method :get :url url} :request-id :sid/get
                :attempt 1 :work/id [:rf.work/http :sid/get 1 1] :status 404}
               (select-keys (:error reply) (remove #{:max-attempts} identity-keys)))))
      (finally (stop-server! srv)))))

(deftest failure-reply-echoes-the-defaulted-method
  ;; Port 1 is reliably closed: connection refused is :rf.http/transport.
  (let [url "http://127.0.0.1:1/x"]
    (rf/reg-event :sid/call-default-method
      (fn [_ _]
        {:fx [[:rf.http/managed {:request {:url url} :on-failure [:sid/failed]}]]}))
    (rf/reg-event :sid/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :reply reply)}))
    (rf/dispatch-sync [:sid/call-default-method])
    (is (= {:kind :rf.http/transport :request {:method :get :url url}}
           (-> (await-reply! #(some? (:reply %))) (get-in [:reply :error]) (select-keys [:kind :request]))))))

(deftest failure-reply-is-self-identifying-5xx-with-retry
  (let [srv (start-server! (fn [^HttpExchange ex] (write-response! ex 503 "text/plain" "down")))]
    (try
      (let [url (str "http://127.0.0.1:" (:port srv) "/down")]
        (is (= {:kind :rf.http/http-5xx :request {:method :get :url url} :request-id :sid/five-xx
                :attempt 3 :max-attempts 3 :work/id [:rf.work/http :sid/five-xx 1 3] :status 503}
               (select-keys (:error (fetch-failure-reply url {:request-id :sid/five-xx :max-attempts 3}))
                            identity-keys))))
      (finally (stop-server! srv)))))

(deftest failure-reply-is-self-identifying-aborted
  (let [gate (java.util.concurrent.CountDownLatch. 1)
        srv  (start-server!
               (fn [^HttpExchange ex]
                 (try (.await gate 2 java.util.concurrent.TimeUnit/SECONDS)
                      (catch InterruptedException _ nil))
                 (write-response! ex 200 "application/json" "{\"v\":1}")))]
    (try
      (let [url (str "http://127.0.0.1:" (:port srv) "/slow")]
        (rf/reg-event :sid/go
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get :url url}
                    :request-id :sid/abort
                    :decode     :json
                    :on-failure [:sid/failed]}]]}))
        (rf/reg-event :sid/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :reply reply)}))
        (rf/reg-event :sid/abort! (fn [_ _] {:fx [[:rf.http/managed-abort :sid/abort]]}))
        (rf/dispatch-sync [:sid/go])
        (rf/dispatch-sync [:sid/abort!])
        (.countDown gate)
        (let [reply (:reply (await-reply! #(some? (:reply %))))]
          (is (= :cancelled (:status reply)))
          (is (= {:kind :rf.http/aborted :request {:method :get :url url} :request-id :sid/abort
                  :work/id [:rf.work/http :sid/abort 1 1]}
                 (select-keys (:error reply) [:kind :request :request-id :work/id])))))
      (finally (stop-server! srv)))))

(deftest supersede-distinct-work-ids-and-canonical-stale-trace
  ;; Both responses are byte-identical, so the work-id's issuance slot is the
  ;; only fact telling "delivered #2" from "delivered #1 and lost #2". The
  ;; supersede runs inline in the second dispatch-sync's fx phase, so the
  ;; stale row and the in-flight slot are read before anything is released,
  ;; and the final dispatch-sync is a FIFO drain barrier that would observe a
  ;; wrongly delivered #1 reply.
  (rf.http.registry/reset-issuance-counters-for-test!)
  (let [release (java.util.concurrent.CountDownLatch. 1)
        replied (java.util.concurrent.CountDownLatch. 1)
        srv     (start-held-server! release)
        replies (atom [])
        traces  (atom [])
        lid     ::supersede-stale]
    (try
      (rf.trace.tooling/register-listener! lid (fn [ev] (swap! traces conj ev)))
      (rf/reg-event :search/replied
        (fn [{:keys [db]} [_ payload]]
          (swap! replies conj payload)
          (.countDown replied)
          {:db db}))
      (rf/reg-event :search/quiesce (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event :search/go
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url (str "http://127.0.0.1:" (:port srv) "/s")}
                  :request-id :search
                  :decode     :json
                  :on-success [:search/replied]
                  :on-failure [:search/replied]}]]}))
      (rf/dispatch-sync [:search/go])
      (rf/dispatch-sync [:search/go])
      (is (= [{:rf.reply/status       :stale
               :rf.reply/work-status  :suppressed
               :rf.reply/stale-reason :rf.http/request-id-superseded
               :rf.reply/work-kind    :http
               :rf.reply/work-id      [:rf.work/http :search 1 1]
               :carried               [:rf.work/http :search 1 1]
               :current               [:rf.work/http :search 2 1]}]
             (->> @traces
                  (filter #(= :rf.http/stale-suppressed (:operation %)))
                  (mapv (fn [{:keys [tags]}]
                          (-> (select-keys tags [:rf.reply/status :rf.reply/work-status :rf.reply/stale-reason
                                                 :rf.reply/work-kind :rf.reply/work-id])
                              (assoc :carried (get-in tags [:rf.reply/carried :work/id])
                                     :current (get-in tags [:rf.reply/current :work/id]))))))))
      (is (= [:rf.work/http :search 2 1]
             (some-> (rf.http.registry/lookup-in-flight :search) rf.http.reply/work-id))
          "issuance 2 owns the in-flight slot before anything is released")
      (.countDown release)
      (is (.await replied 30 java.util.concurrent.TimeUnit/SECONDS))
      (rf/dispatch-sync [:search/quiesce])
      (is (= [[:ok [:rf.work/http :search 2 1]]] (mapv (juxt :status :rf.reply/work-id) @replies))
          "exactly one app reply, and it is the superseding issuance's")
      (finally
        (.countDown release)
        (rf.trace.tooling/unregister-listener! lid)
        (stop-server! srv)))))

(deftest http-reply-declared-handler-receives-completion-time-flat
  ;; The router's own missing-cofx fill coincides with :completed-at to the
  ;; millisecond, so the reply dispatch's opts are captured to show HTTP
  ;; supplies the flat :rf.cofx itself.
  (let [srv            (start-server!
                         (fn [^HttpExchange ex]
                           (write-response! ex 200 "application/json" "{\"v\":1}")))
        cofx           (atom ::unset)
        traces         (atom [])
        dispatch-opts  (atom nil)
        real-dispatch! (rf.late-bind/get-fn :router/dispatch!)
        lid            ::cofx-time]
    (try
      (rf.late-bind/set-fn! :router/dispatch!
        (fn [ev opts]
          (when (= :svc/replied (first ev))
            (reset! dispatch-opts opts))
          (real-dispatch! ev opts)))
      (rf.trace.tooling/register-listener! lid (fn [ev] (swap! traces conj ev)))
      (rf/reg-event :svc/call
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url (str "http://127.0.0.1:" (:port srv) "/c")}
                  :request-id :svc/call
                  :decode     :json
                  :on-success [:svc/replied]}]]}))
      (rf/reg-event :svc/replied
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [db] :as coeffects} _]
          (reset! cofx coeffects)
          {:db (assoc db :done true)}))
      (rf/dispatch-sync [:svc/call])
      (await-reply! #(:done %))
      (let [c            @cofx
            tags         (->> @traces (filter #(= :rf.http/replied (:operation %))) first :tags)
            completed-at (:completed-at tags)]
        (is (number? completed-at))
        (is (= {:status :ok :rf.reply/work-kind :http :rf.reply/work-id [:rf.work/http :svc/call 1 1]
                :correlation {:request-id :svc/call}}
               (select-keys tags [:status :rf.reply/work-kind :rf.reply/work-id :correlation]))
            "the :rf.http/replied row carries the canonical envelope facts")
        (is (= [{:rf/time-ms completed-at} :http] ((juxt :rf.cofx :source) @dispatch-opts)))
        (is (= [completed-at completed-at] [(:rf/time-ms c) (get-in c [:rf.cofx :rf/time-ms])])
            "the handler's :rf/time-ms is the HTTP completion time, not a fresh clock read"))
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real-dispatch!)
        (rf.trace.tooling/unregister-listener! lid)
        (stop-server! srv)))))

(deftest http-reply-undeclared-handler-does-not-see-implicit-time
  (let [srv  (start-server!
               (fn [^HttpExchange ex]
                 (write-response! ex 200 "application/json" "{\"v\":1}")))
        cofx (atom ::unset)]
    (try
      (rf/reg-event :svc/call2
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url (str "http://127.0.0.1:" (:port srv) "/c")}
                  :decode     :json
                  :on-success [:svc/replied2]}]]}))
      (rf/reg-event :svc/replied2
        (fn [{:keys [db] :as coeffects} _]
          (reset! cofx coeffects)
          {:db (assoc db :done2 true)}))
      (rf/dispatch-sync [:svc/call2])
      (await-reply! #(:done2 %))
      (is (not (contains? @cofx :rf/time-ms)))
      (is (map? (:rf.cofx @cofx)) "the flat :rf.cofx record stays reachable")
      (finally (stop-server! srv)))))
