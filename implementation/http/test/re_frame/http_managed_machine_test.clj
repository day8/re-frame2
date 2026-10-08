(ns re-frame.http-managed-machine-test
  "Spec 014 §Machine-shape wrapper: `:rf.http/managed` is also a
  child-invokable machine. A parent `:spawn`s it with `{:request ...}` in
  `:data`; the wrapper fires the fx on entry and replies to its parent (via the
  injected `:rf/parent-id`) with `[:succeeded value]` or `[:failed failure]`.
  Destroying the wrapper aborts its in-flight request through the actor-destroy
  cascade. A parent's `:after` leaving the state uses the same cascade, which
  the machines suite pins."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-server!
  [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx
                 (reify HttpHandler
                   (handle [_ exchange]
                     (handler exchange))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}]
  (.stop server 0))

(defn- write-response! [^HttpExchange exchange status content-type body]
  (let [bytes (.getBytes (str body) "UTF-8")]
    (when content-type
      (-> exchange .getResponseHeaders (.set "Content-Type" content-type)))
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- start-blocking-server!
  "Server that holds each exchange on `latch`, then writes `body` with `status`."
  [^CountDownLatch latch status content-type body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex]
                          (.await latch 30 TimeUnit/SECONDS)
                          (let [bs (.getBytes (str body) "UTF-8")]
                            (when content-type
                              (-> ex .getResponseHeaders (.set "Content-Type" content-type)))
                            (try
                              (.sendResponseHeaders ex status (long (count bs)))
                              (with-open [os (.getResponseBody ex)]
                                (.write os bs))
                              (catch Throwable _ nil)))
                          nil))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))}))

(defn- await-condition! [pred]
  (rf.test-support/poll-until pred {:timeout-ms 5000 :interval-ms 10
                                    :label "http-managed-machine condition"}))

(defn- snapshot [machine-id]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/machines :snapshots machine-id]))

(deftest spawn-success-parent-transitions-via-succeeded
  (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"id\":42}")))]
      (try
        (rf/reg-machine :app/auth
          {:initial :idle
           :data    {:result nil}
           :actions {:store-result
                     (fn [{data :data ev :event}]
                       {:data (assoc data :result (second ev))})}
           :states
           {:idle {:on {:login :authenticating}}

            :authenticating
            {:spawn {:machine-id :rf.http/managed
                      :data       {:request {:url    (str "http://127.0.0.1:" port "/api/me")
                                              :method :get}
                                   :decode  :json}}
             :on     {:succeeded {:target :authenticated :action :store-result}
                      :failed    {:target :login-failed}}}

            :authenticated {}
            :login-failed  {}}})
        (rf/dispatch-sync [:app/auth [:login]])
        (await-condition!
          #(= :authenticated (:state (snapshot :app/auth))))
        (is (= {:id 42} (:result (:data (snapshot :app/auth))))
            "the :succeeded event carries the decoded value")
        (finally (stop-server! srv)))))

(deftest spawn-failure-parent-transitions-via-failed
  (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 404 "application/json" "{\"error\":\"not-found\"}")))]
      (try
        (rf/reg-machine :app/auth2
          {:initial :idle
           :data    {:failure nil}
           :actions {:record-failure
                     (fn [{data :data ev :event}]
                       {:data (assoc data :failure (second ev))})}
           :states
           {:idle {:on {:login :authenticating}}

            :authenticating
            {:spawn {:machine-id :rf.http/managed
                      :data       {:request {:url    (str "http://127.0.0.1:" port "/api/me")
                                              :method :get}
                                   :decode  :json}}
             :on     {:succeeded {:target :authenticated}
                      :failed    {:target :login-failed :action :record-failure}}}

            :authenticated {}
            :login-failed  {}}})
        (rf/dispatch-sync [:app/auth2 [:login]])
        (await-condition!
          #(= :login-failed (:state (snapshot :app/auth2))))
        (is (= {:kind :rf.http/http-4xx :status 404}
               (select-keys (:failure (:data (snapshot :app/auth2))) [:kind :status])))
        (finally (stop-server! srv)))))

(deftest spawn-cancellation-parent-destroys-mid-flight
  (let [latch (CountDownLatch. 1)
          srv   (start-blocking-server! latch 200 "application/json" "{}")
          {:keys [port]} srv
          traces (atom [])]
      (try
        (rf.trace.tooling/register-listener! ::ijm7-3 (fn [ev] (swap! traces conj ev)))
        (rf/reg-machine :app/cancel
          {:initial :idle
           :states
           {:idle {:on {:login :authenticating}}

            :authenticating
            {:spawn {:machine-id :rf.http/managed
                      :data       {:request {:url    (str "http://127.0.0.1:" port "/slow")
                                              :method :get}
                                   :decode  :json}}
             :on     {:cancel    :idle
                      :succeeded :authenticated
                      :failed    :idle}}

            :authenticated {}}})
        (rf/dispatch-sync [:app/cancel [:login]])
        (await-condition! #(seq (rf.http.managed/actor-in-flight-snapshot)))
        (is (= #{:rf.http/managed#1} (set (keys (rf.http.managed/actor-in-flight-snapshot))))
            "in flight under the spawned wrapper actor's id")
        ;; Leaving the parent state destroys the wrapper child.
        (rf/dispatch-sync [:app/cancel [:cancel]])
        (is (empty? (rf.http.managed/actor-in-flight-snapshot)))
        (is (= [:rf.http/managed#1]
               (->> @traces
                    (filter #(= :rf.http/aborted-on-actor-destroy (:operation %)))
                    (mapv (comp :actor-id :tags)))))
        (.countDown latch)
        (finally
          (rf.trace.tooling/unregister-listener! ::ijm7-3)
          (stop-server! srv)))))

(defn- live-wrapper-addresses []
  (->> (keys (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                     [:rf.runtime/machines :snapshots]))
       (filter #(and (keyword? %) (= "rf.http" (namespace %))
                     (.startsWith ^String (name %) "managed#")))))

(deftest spawn-loop-leaves-no-anonymous-issuance-counters
  ;; Each spawn issues one anonymous request under a fresh actor address, so the
  ;; counter keyed by that address must go when the actor is destroyed.
  (let [cycles 5
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf.http.registry/reset-issuance-counters-for-test!)
        (rf/reg-machine :app/poller
          {:initial :idle
           :states
           {:idle     {:on {:go :fetching}}
            :fetching {:spawn {:machine-id :rf.http/managed
                               :data       {:request {:url    (str "http://127.0.0.1:" port "/poll")
                                                      :method :get}
                                            :decode  :json}}
                       :on    {:succeeded :idle
                               :failed    :idle}}}})
        (dotimes [_ cycles]
          (rf/dispatch-sync [:app/poller [:go]])
          (await-condition! #(and (= :idle (:state (snapshot :app/poller)))
                                  (empty? (live-wrapper-addresses))
                                  (empty? (rf.http.managed/actor-in-flight-snapshot)))))
        ;; The cascade that drops the counter runs on the event thread.
        (try (rf.test-support/poll-until
               #(zero? (rf.http.registry/anonymous-issuance-counter-count))
               {:timeout-ms 1000 :interval-ms 10 :label "anonymous counters drained"})
             (catch clojure.lang.ExceptionInfo _ nil))
        (is (zero? (rf.http.registry/anonymous-issuance-counter-count))
            (str cycles " completed spawns left "
                 (rf.http.registry/anonymous-issuance-counter-count)
                 " anonymous issuance counters behind"))
        (finally (stop-server! srv)))))

(deftest spawn-data-reply-addressing-is-refused-at-spawn
  ;; The wrapper owns its reply, so reply addressing in the spawn :data is
  ;; refused on entry, before any request goes out, and the parent's :on-error
  ;; moves it instead of leaving it waiting in the spawning state.
  (let [hits (atom 0)
        {:keys [port] :as srv}
        (start-server!
          (fn [^HttpExchange ex]
            (swap! hits inc)
            (write-response! ex 200 "application/json" "{\"ok\":true}")))
        traces (atom [])]
    (try
      (rf.trace.tooling/register-listener! ::reply-keys (fn [ev] (swap! traces conj ev)))
      (rf/reg-machine :app/reply-keys
        {:initial :idle
         :states
         {:idle    {:on {:go :loading}}
          :loading {:spawn {:machine-id :rf.http/managed
                            :data       {:request  {:url (str "http://127.0.0.1:" port "/me")}
                                         :decode   :json
                                         :reply-to [:app/somewhere]}
                            :on-error   :load-failed}
                    :on    {:succeeded :ready
                            :failed    :load-failed}}
          :ready       {}
          :load-failed {}}})
      (rf/dispatch-sync [:app/reply-keys [:go]])
      (await-condition! #(= :load-failed (:state (snapshot :app/reply-keys))))
      (is (= [{:reason :machine-owns-reply :keys [:reply-to]}]
             (->> @traces
                  (filter #(= :rf.error/machine-action-exception (:operation %)))
                  (map #(get-in % [:tags :exception-data]))
                  (filter #(= :rf.error/http-bad-reply-target (:rf.error/id %)))
                  (mapv #(select-keys % [:reason :keys])))))
      (is (= [false false 0]
             [(boolean (some #(= :rf.error/fx-handler-exception (:operation %)) @traces))
              (boolean (seq (rf.http.managed/actor-in-flight-snapshot)))
              @hits])
          "the fx never ran, nothing is in flight, and nothing reached the server")
        (finally
          (rf.trace.tooling/unregister-listener! ::reply-keys)
          (stop-server! srv)))))
