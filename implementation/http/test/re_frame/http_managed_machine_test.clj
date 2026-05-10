(ns re-frame.http-managed-machine-test
  "Per rf2-ijm7 and Spec 014 §Machine-shape wrapper. Verifies that
  `:rf.http/managed` is also registered as a child-invokable state
  machine — so a parent machine can `:invoke` it and observe success /
  failure via ordinary `:succeeded` / `:failed` events back from the
  child.

  The wrapper machine's contract:
   - `:invoke {:machine-id :rf.http/managed :data {:request {...}}}`
     on a parent's state spawns the wrapper actor, which fires the
     underlying `:rf.http/managed` fx on its `:requesting` entry and
     transitions to `:succeeded` / `:failed` on the reply.
   - The terminal entry-action dispatches `[<parent-id> [:succeeded
     value]]` (or `[:failed failure]`) back to the parent — addressing
     resolves via `:rf/parent-id` injected into the wrapper actor's
     initial `:data` by spawn-fx (per rf2-ijm7).
   - Cancellation composes with rf2-wvkn: when the parent destroys
     the wrapper child (parent state exit, parent's `:after` firing,
     etc.), the in-flight HTTP aborts and the abort cascade fires
     `:rf.http/aborted-on-actor-destroy`.

  Coverage:
   1. Parent :invoke + success → parent transitions via :succeeded.
   2. Parent :invoke + failure → parent transitions via :failed.
   3. Parent destroys child mid-flight → request aborts (rf2-wvkn).
   4. Composes with :after — wall-clock timeout cancels in-flight.

  Tests run on JVM through the plain-atom substrate; the CLJS path
  uses the same wrapper registration via Fetch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http-managed :as http-managed]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http-managed :reload)
  (machines/reset-counters!)
  (http-managed/clear-all-in-flight!)
  (t))

(use-fixtures :each reset-runtime)

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
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "timed out awaiting condition" {}))
         :else (do (Thread/sleep 10) (recur)))))))

(defn- snapshot [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

;; ---- (1) :invoke + success → parent transitions via :succeeded ------------

(deftest invoke-success-parent-transitions-via-succeeded
  (testing "parent :invoke {:machine-id :rf.http/managed ...} + 2xx + 2xx body → parent's :on :succeeded fires"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"id\":42}")))]
      (try
        (rf/reg-machine :app/auth
          {:initial :idle
           :data    {:result nil}
           :actions {:store-result
                     (fn [data ev]
                       ;; The :succeeded event carries the value as the
                       ;; second element (the runtime folded :rf/parent-id
                       ;; dispatch's payload into the inner event).
                       {:data (assoc data :result (second ev))})}
           :states
           {:idle {:on {:login :authenticating}}

            :authenticating
            {:invoke {:machine-id :rf.http/managed
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
        (is (= :authenticated (:state (snapshot :app/auth))))
        (is (= {:id 42} (:result (:data (snapshot :app/auth))))
            "the success value was propagated through the wrapper's :succeeded event")
        (finally (stop-server! srv))))))

;; ---- (2) :invoke + failure → parent transitions via :failed ---------------

(deftest invoke-failure-parent-transitions-via-failed
  (testing "parent :invoke + 4xx → wrapper :failed → parent's :on :failed fires"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 404 "application/json" "{\"error\":\"not-found\"}")))]
      (try
        (rf/reg-machine :app/auth2
          {:initial :idle
           :data    {:failure nil}
           :actions {:record-failure
                     (fn [data ev]
                       {:data (assoc data :failure (second ev))})}
           :states
           {:idle {:on {:login :authenticating}}

            :authenticating
            {:invoke {:machine-id :rf.http/managed
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
        (is (= :login-failed (:state (snapshot :app/auth2))))
        (let [failure (:failure (:data (snapshot :app/auth2)))]
          (is (= :rf.http/http-4xx (:kind failure))
              "the failure payload preserves the :rf.http/* category")
          (is (= 404 (:status failure))))
        (finally (stop-server! srv))))))

;; ---- (3) parent destroys child mid-flight → HTTP aborts ------------------

(deftest invoke-cancellation-parent-destroys-mid-flight
  (testing "parent state-exit destroys the wrapper actor, which aborts the in-flight HTTP per rf2-wvkn"
    (let [latch (CountDownLatch. 1)
          srv   (start-blocking-server! latch 200 "application/json" "{}")
          {:keys [port]} srv
          traces (atom [])]
      (try
        (trace/register-trace-cb! ::ijm7-3 (fn [ev] (swap! traces conj ev)))
        (rf/reg-machine :app/cancel
          {:initial :idle
           :states
           {:idle {:on {:login :authenticating}}

            :authenticating
            {:invoke {:machine-id :rf.http/managed
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
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        (let [snap (http-managed/actor-in-flight-snapshot)]
          (is (= 1 (count snap)))
          (is (contains? snap :rf.http/managed#1)
              "in-flight indexed by the spawned wrapper actor's id"))
        ;; Parent cancels — the parent state exits, the wrapper child
        ;; is destroyed, and the abort cascade fires.
        (rf/dispatch-sync [:app/cancel [:cancel]])
        (is (= :idle (:state (snapshot :app/cancel))))
        (is (empty? (http-managed/actor-in-flight-snapshot))
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
          (trace/remove-trace-cb! ::ijm7-3)
          (stop-server! srv))))))

;; ---- (4) composes with :after — whichever fires first wins ----------------

(deftest invoke-composes-with-after-timeout
  (testing "parent state with both :invoke {:machine-id :rf.http/managed} AND :after {ms target} — :after firing cancels the wrapper"
    (let [latch (CountDownLatch. 1)
          srv   (start-blocking-server! latch 200 "application/json" "{}")
          {:keys [port]} srv
          traces (atom [])]
      (try
        (trace/register-trace-cb! ::ijm7-4 (fn [ev] (swap! traces conj ev)))
        (rf/reg-machine :app/after-test
          {:initial :idle
           :states
           {:idle {:on {:login :authenticating}}

            :authenticating
            ;; :after acts as a wall-clock timeout that spans the
            ;; wrapper's behaviour (Spec 005 §Wall-clock timeouts on
            ;; :invoke — use parent state's :after). When the :after
            ;; fires before the HTTP response, the parent transitions
            ;; to :timed-out and the standard exit cascade tears down
            ;; the :rf.http/managed wrapper child.
            {:invoke {:machine-id :rf.http/managed
                      :data       {:request {:url    (str "http://127.0.0.1:" port "/slow")
                                              :method :get}
                                   :decode  :json}}
             :after  {30000 :timed-out}
             :on     {:succeeded :authenticated
                      :failed    :idle}}

            :authenticated {}
            :timed-out     {}}})
        (rf/dispatch-sync [:app/after-test [:login]])
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        (is (contains? (http-managed/actor-in-flight-snapshot)
                       :rf.http/managed#1))
        ;; Simulate the :after firing by dispatching the synthetic
        ;; timer-elapsed event directly (the after_test.clj pattern —
        ;; the wall-clock would fire it at 30000ms; the synthetic event
        ;; drives the same code path deterministically).
        (let [epoch (or (get-in (snapshot :app/after-test)
                                [:data :rf/after-epoch])
                        0)]
          (rf/dispatch-sync [:app/after-test
                             [:rf.machine.timer/after-elapsed 30000 epoch]]))
        (is (= :timed-out (:state (snapshot :app/after-test)))
            ":after firing exited :authenticating to :timed-out")
        (is (empty? (http-managed/actor-in-flight-snapshot))
            "the cancellation cascade aborted the in-flight HTTP")
        (let [abort-traces (filter #(= :rf.http/aborted-on-actor-destroy
                                       (:operation %))
                                   @traces)]
          (is (seq abort-traces)
              "wall-clock timeout cancelled the wrapper child + fired the rf2-wvkn trace"))
        (.countDown latch)
        (finally
          (trace/remove-trace-cb! ::ijm7-4)
          (stop-server! srv))))))

;; ---- (5) sibling fx-form continues to work alongside the machine wrapper -

(deftest fx-form-coexists-with-machine-wrapper
  (testing "the :fx form `:fx [[:rf.http/managed args]]` continues to work; fx and machine registrations under the same id coexist"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event-fx :legacy/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              (case (:kind reply)
                :success {:db (assoc db :result (:value reply))}
                :failure {:db (assoc db :error (:failure reply))})
              {:fx [[:rf.http/managed
                     {:request {:url    (str "http://127.0.0.1:" port "/")
                                :method :get}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:legacy/load {}])
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop []
            (cond
              (some? (:result (rf/get-frame-db :rf/default))) nil
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "timed out awaiting fx-form reply" {}))
              :else (do (Thread/sleep 25) (recur)))))
        (is (= {:ok true} (:result (rf/get-frame-db :rf/default)))
            "the fx-form `:rf.http/managed` continues to dispatch back the standard reply envelope")
        (finally (stop-server! srv))))))
