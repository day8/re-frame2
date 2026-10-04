(ns re-frame.http-managed-machine-test
  "Per Spec 014 §Machine-shape wrapper. Verifies that
  `:rf.http/managed` is also registered as a child-invokable state
  machine — so a parent machine can `:spawn` it and observe success /
  failure via ordinary `:succeeded` / `:failed` events back from the
  child.

  The wrapper machine's contract:
   - `:spawn {:machine-id :rf.http/managed :data {:request {...}}}`
     on a parent's state spawns the wrapper actor, which fires the
     underlying `:rf.http/managed` fx on its `:requesting` entry and
     transitions to `:succeeded` / `:failed` on the reply.
   - The terminal entry-action dispatches `[<parent-id> [:succeeded
     value]]` (or `[:failed failure]`) back to the parent — addressing
     resolves via `:rf/parent-id` injected into the wrapper actor's
     initial `:data` by spawn-fx.
   - Cancellation composes with the actor-destroy abort: when the parent destroys
     the wrapper child (parent state exit, parent's `:after` firing,
     etc.), the in-flight HTTP aborts and the abort cascade fires
     `:rf.http/aborted-on-actor-destroy`.

  Coverage:
   1. Parent :spawn + success → parent transitions via :succeeded. The
      wrapper resolves as a machine and issues its request as the fx of the
      same id, so this also runs the two registrations side by side.
   2. Parent :spawn + failure → parent transitions via :failed.
   3. Parent destroys child mid-flight → request aborts. A parent's `:after`
      firing leaves the state through the same destroy cascade, which the
      machines suite pins (`after-on-spawn-bearing-state-tears-down-child`).
   4. A spawn loop leaves no anonymous issuance counters.
   5. Reply addressing in the spawn `:data` is refused on entry, before any
      request goes out, and the refusal reaches the parent's `:on-error`.

  Tests run on JVM through the plain-atom substrate; the CLJS path
  uses the same wrapper registration via Fetch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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

;; ---- per-test reset --------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

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
  "Server that blocks on `latch` until released, then writes `body`
  with `status`. Used for cancellation tests where the in-flight
  request must remain pending while the test mutates state."
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

(defn- await-condition!
  "Thin alias over `test-support/poll-until` with the
  per-file arity (`pred`, optional `timeout-ms`)."
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-managed-machine condition"})
   true))

(defn- snapshot [machine-id]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/machines :snapshots machine-id]))

;; ---- (1) :spawn + success → parent transitions via :succeeded ------------

(deftest spawn-success-parent-transitions-via-succeeded
  (testing "parent :spawn {:machine-id :rf.http/managed ...} + 2xx + 2xx body → parent's :on :succeeded fires"
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
                       ;; The :succeeded event carries the value as the
                       ;; second element (the runtime folded :rf/parent-id
                       ;; dispatch's payload into the inner event).
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
        ;; Allow async dispatches to drain (the wrapper's reply +
        ;; the parent's :succeeded chain land via dispatch!, not
        ;; synchronously inside the original dispatch-sync).
        (await-condition!
          #(= :authenticated (:state (snapshot :app/auth))))
        (is (= {:id 42} (:result (:data (snapshot :app/auth))))
            "the success value was propagated through the wrapper's :succeeded event")
        (finally (stop-server! srv))))))

;; ---- (2) :spawn + failure → parent transitions via :failed ---------------

(deftest spawn-failure-parent-transitions-via-failed
  (testing "parent :spawn + 4xx → wrapper :failed → parent's :on :failed fires"
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
        (let [failure (:failure (:data (snapshot :app/auth2)))]
          (is (= :rf.http/http-4xx (:kind failure))
              "the failure payload preserves the :rf.http/* category")
          (is (= 404 (:status failure))))
        (finally (stop-server! srv))))))

;; ---- (3) parent destroys child mid-flight → HTTP aborts ------------------

(deftest spawn-cancellation-parent-destroys-mid-flight
  (testing "parent state-exit destroys the wrapper actor, which aborts the in-flight HTTP"
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
        ;; Wait until the wrapper's underlying fx has recorded the
        ;; request in-flight against the wrapper actor's id.
        (await-condition! #(seq (rf.http.managed/actor-in-flight-snapshot)))
        (let [snap (rf.http.managed/actor-in-flight-snapshot)]
          (is (= 1 (count snap)))
          (is (contains? snap :rf.http/managed#1)
              "in-flight indexed by the spawned wrapper actor's id"))
        ;; Parent cancels — the parent state exits, the wrapper child
        ;; is destroyed, and the abort cascade fires.
        (rf/dispatch-sync [:app/cancel [:cancel]])
        (is (= :idle (:state (snapshot :app/cancel))))
        (is (empty? (rf.http.managed/actor-in-flight-snapshot))
            "in-flight registry cleared after parent's cancel")
        (let [abort-traces (filter #(= :rf.http/aborted-on-actor-destroy
                                       (:operation %))
                                   @traces)]
          (is (seq abort-traces)
              ":rf.http/aborted-on-actor-destroy trace fired")
          (let [tags (:tags (first abort-traces))]
            (is (= :rf.http/managed#1 (:actor-id tags))
                "trace identifies the destroyed wrapper actor")))
        (.countDown latch)
        (finally
          (rf.trace.tooling/unregister-listener! ::ijm7-3)
          (stop-server! srv))))))

;; ---- (4) a spawn loop leaves no issuance counters ------------------------

(defn- live-wrapper-addresses []
  (->> (keys (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                     [:rf.runtime/machines :snapshots]))
       (filter #(and (keyword? %) (= "rf.http" (namespace %))
                     (.startsWith ^String (name %) "managed#")))))

(deftest spawn-loop-leaves-no-anonymous-issuance-counters
  (testing "each spawn of the :rf.http/managed wrapper issues
            one anonymous request under a fresh actor address, so the per-(frame,
            event-id) counter keyed by that address must go when the actor is
            destroyed; otherwise a long-lived frame keeps one entry per spawn"
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
        ;; The destroy cascade that drops the counter runs on the event thread;
        ;; give its tail a bounded moment rather than racing it.
        (try (rf.test-support/poll-until
               #(zero? (rf.http.registry/anonymous-issuance-counter-count))
               {:timeout-ms 1000 :interval-ms 10 :label "anonymous counters drained"})
             (catch clojure.lang.ExceptionInfo _ nil))
        (is (zero? (rf.http.registry/anonymous-issuance-counter-count))
            (str cycles " completed spawns left "
                 (rf.http.registry/anonymous-issuance-counter-count)
                 " anonymous issuance counters behind"))
        (finally (stop-server! srv))))))

;; ---- (5) reply addressing in :data is refused at spawn -------------------

(deftest spawn-data-reply-addressing-is-refused-at-spawn
  (testing "a :spawn whose :data carries :reply-to, :on-success or :on-failure
            is refused on the wrapper's entry with :rf.error/http-bad-reply-target
            (:reason :machine-owns-reply): no request goes out, and the parent's
            :on-error moves it instead of leaving it waiting in the spawning state"
    (let [hits (atom 0)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (swap! hits inc)
              (write-response! ex 200 "application/json" "{\"ok\":true}")))
          traces (atom [])]
      (try
        (rf.trace.tooling/register-listener! ::reply-keys (fn [ev] (swap! traces conj ev)))
        (doseq [k [:reply-to :on-success :on-failure]]
          (let [parent (keyword "app" (str "reply-keys-" (name k)))]
            (reset! traces [])
            (rf/reg-machine parent
              {:initial :idle
               :states
               {:idle    {:on {:go :loading}}
                :loading {:spawn {:machine-id :rf.http/managed
                                  :data       {:request {:url (str "http://127.0.0.1:" port "/me")}
                                               :decode  :json
                                               k        [:app/somewhere]}
                                  :on-error   :load-failed}
                          :on    {:succeeded :ready
                                  :failed    :load-failed}}
                :ready       {}
                :load-failed {}}})
            (rf/dispatch-sync [parent [:go]])
            ;; The parent leaves :loading through :on-error, or this times out.
            (await-condition! #(= :load-failed (:state (snapshot parent))))
            (let [refusal (->> @traces
                               (filter #(= :rf.error/machine-action-exception (:operation %)))
                               (map #(get-in % [:tags :exception-data]))
                               (filter #(= :rf.error/http-bad-reply-target (:rf.error/id %)))
                               first)]
              (is (= {:reason :machine-owns-reply :keys [k]}
                     (select-keys refusal [:reason :keys]))
                  (str k " in :data: the named refusal names the key")))
            (is (empty? (filter #(= :rf.error/fx-handler-exception (:operation %)) @traces))
                (str k " in :data: the fx never ran, so it refused nothing itself"))
            (is (empty? (rf.http.managed/actor-in-flight-snapshot))
                (str k " in :data: no request is in flight"))))
        (is (zero? @hits) "no request reached the server")
        (finally
          (rf.trace.tooling/unregister-listener! ::reply-keys)
          (stop-server! srv))))))
