(ns re-frame.http-interceptors-test
  "JVM tests for Spec 014 §Middleware — per-frame request interceptor
  chain (rf2-6y3q).

  Invariants exercised:

  1. Single interceptor transforms the outgoing :request before transport.
  2. Multi-interceptor chain executes in registration order.
  3. Frame-scoped — an interceptor on frame A does not fire for a request
     dispatched from frame B.
  4. Throw-recovery — a `:before` that throws raises
     `:rf.error/http-interceptor-failed`; the request is not dispatched
     and a trace error fires.
  5. `clear-http-interceptor` unregisters cleanly; subsequent requests
     are unaffected.
  6. Re-registering an id replaces the slot in place (registration order
     preserved).

  We use a JDK `com.sun.net.httpserver.HttpServer` (same harness as
  http-managed-test) so the request the transport actually emits is
  observable via the server-side handler — the test asserts on the
  headers / body the server sees, which is the load-bearing
  transformation the interceptor produced."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.http-managed :as http-managed]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http-managed :reload)
  (http-managed/clear-all-in-flight!)
  (http-managed/clear-all-http-interceptors!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- in-process server harness --------------------------------------------

(defn- start-server!
  [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx
                 (reify HttpHandler
                   (handle [_ exchange] (handler exchange))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}] (.stop server 0))

(defn- write-response! [^HttpExchange ex status content-type body]
  (let [bytes (.getBytes (str body) "UTF-8")]
    (when content-type
      (-> ex .getResponseHeaders (.set "Content-Type" content-type)))
    (.sendResponseHeaders ex status (long (count bytes)))
    (with-open [os (.getResponseBody ex)] (.write os bytes))))

(defn- header-of [^HttpExchange ex name]
  (-> ex .getRequestHeaders (.getFirst name)))

(defn- await-reply!
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [db (rf/get-frame-db :rf/default)]
         (cond
           (pred db) db
           (> (System/currentTimeMillis) deadline)
           (throw (ex-info "timed out awaiting reply" {:final-db db}))
           :else (do (Thread/sleep 25) (recur))))))))

;; ---- 1. single interceptor transforms the outgoing request ----------------

(deftest single-interceptor-transforms-request
  (testing "a registered :before interceptor's modifications reach the transport"
    (let [seen-auth (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (reset! seen-auth (header-of ex "Authorization"))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-http-interceptor
          {:id     :auth-header
           :before (fn [ctx]
                     (assoc-in ctx [:request :headers "Authorization"]
                               "Bearer secret-token-42"))})
        (rf/reg-event-fx :load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/secured")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:load])
        (await-reply! #(some? (:reply %)) 5000)
        (is (= "Bearer secret-token-42" @seen-auth)
            "the server saw the Authorization header the interceptor injected")
        (finally (stop-server! srv))))))

;; ---- 2. multi-interceptor chain order -------------------------------------

(deftest multi-interceptor-runs-in-registration-order
  (testing "interceptors fire in registration order; later ones see earlier outputs"
    (let [order (atom [])
          seen-headers (atom {})
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (reset! seen-headers
                      {"X-One"   (header-of ex "X-One")
                       "X-Two"   (header-of ex "X-Two")
                       "X-Three" (header-of ex "X-Three")})
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (rf/reg-http-interceptor
          {:id     :first
           :before (fn [ctx]
                     (swap! order conj :first)
                     (assoc-in ctx [:request :headers "X-One"] "1"))})
        (rf/reg-http-interceptor
          {:id     :second
           :before (fn [ctx]
                     (swap! order conj :second)
                     ;; verify earlier interceptor's output is visible
                     (is (= "1" (get-in ctx [:request :headers "X-One"])))
                     (assoc-in ctx [:request :headers "X-Two"] "2"))})
        (rf/reg-http-interceptor
          {:id     :third
           :before (fn [ctx]
                     (swap! order conj :third)
                     (is (= "2" (get-in ctx [:request :headers "X-Two"])))
                     (assoc-in ctx [:request :headers "X-Three"] "3"))})
        (rf/reg-event-fx :load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:load])
        (await-reply! #(some? (:reply %)) 5000)
        (is (= [:first :second :third] @order)
            "registration order is preserved")
        (is (= {"X-One" "1" "X-Two" "2" "X-Three" "3"} @seen-headers)
            "all three interceptor outputs reached the wire")
        (finally (stop-server! srv))))))

;; ---- 3. frame-scoped — interceptor on frame A does not fire on frame B ----

(deftest interceptor-is-frame-scoped
  (testing "an interceptor registered on frame A does not transform frame B's requests"
    (let [seen-on-default (atom nil)
          seen-on-other   (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (let [path (.getPath (.getRequestURI ex))]
                (cond
                  (.startsWith path "/from-default")
                  (reset! seen-on-default (header-of ex "X-Marker"))

                  (.startsWith path "/from-other")
                  (reset! seen-on-other (header-of ex "X-Marker")))
                (write-response! ex 200 "application/json" "{}"))))]
      (try
        (rf/reg-frame :other-frame {:doc "alt frame"})
        ;; Register interceptor ONLY on :rf/default. :other-frame has none.
        (rf/reg-http-interceptor
          {:frame  :rf/default
           :id     :marker
           :before (fn [ctx]
                     (assoc-in ctx [:request :headers "X-Marker"] "default-only"))})
        ;; Two events — one on each frame — both fire :rf.http/managed.
        (rf/reg-event-fx :load-default
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/from-default")}
                    :decode  :json
                    :on-success nil}]]}))
        (rf/reg-event-fx :load-other
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/from-other")}
                    :decode  :json
                    :on-success nil}]]}))
        (rf/dispatch-sync [:load-default])
        (rf/dispatch-sync [:load-other] {:frame :other-frame})
        ;; Wait for both server hits.
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop []
            (when (and (< (System/currentTimeMillis) deadline)
                       (or (nil? @seen-on-default)
                           (and (nil? @seen-on-other)
                                ;; for the other frame, we expect nil header but
                                ;; we still need to know the request fired —
                                ;; sleep a beat then check again
                                true)))
              (Thread/sleep 50)
              (recur))))
        (Thread/sleep 200) ;; give the second request time to land
        (is (= "default-only" @seen-on-default)
            "default-frame request carried the interceptor's header")
        (is (nil? @seen-on-other)
            "other-frame request did NOT carry the header — interceptor is frame-scoped")
        (finally (stop-server! srv))))))

;; ---- 4. throw-recovery: failed interceptor raises and skips the request ---

(deftest interceptor-throw-raises-and-skips-dispatch
  (testing "a throwing :before raises :rf.error/http-interceptor-failed and the request is not dispatched"
    (let [traces      (atom [])
          server-hits (atom 0)
          listener-id (gensym "interceptor-test-")
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (swap! server-hits inc)
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (trace/register-trace-cb! listener-id
                                  (fn [ev] (swap! traces conj ev)))
        (rf/reg-http-interceptor
          {:id     :boom
           :before (fn [_ctx]
                     (throw (ex-info "kaboom" {:detail :synthetic})))})
        (rf/reg-event-fx :load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/x")}
                    :decode  :json
                    :on-success nil
                    :on-failure nil}]]}))
        ;; The runtime's fx wrapper catches the throw and emits
        ;; :rf.error/fx-handler-exception (per re-frame.fx). The
        ;; user-observable surface for an interceptor failure is two-fold:
        ;; (a) the request is NOT dispatched (server saw nothing), and
        ;; (b) :rf.error/http-interceptor-failed appears on the trace
        ;; stream so tools / 10x panels can attribute the failure.
        (rf/dispatch-sync [:load])
        ;; Give the runtime a moment in case anything is async.
        (Thread/sleep 100)
        (is (zero? @server-hits)
            "request was NOT dispatched — server saw zero requests")
        (let [interceptor-fail? (some #(= :rf.error/http-interceptor-failed
                                          (:operation %))
                                      @traces)]
          (is interceptor-fail?
              ":rf.error/http-interceptor-failed appears on the trace stream"))
        (finally
          (trace/remove-trace-cb! listener-id)
          (stop-server! srv))))))

;; ---- 5. clear-http-interceptor unregisters cleanly ------------------------

(deftest clear-http-interceptor-unregisters
  (testing "clear-http-interceptor removes the slot; subsequent requests are unaffected"
    (let [seen-auth (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (reset! seen-auth (header-of ex "Authorization"))
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (rf/reg-http-interceptor
          {:id     :auth-header
           :before (fn [ctx]
                     (assoc-in ctx [:request :headers "Authorization"]
                               "Bearer A"))})
        (rf/reg-event-fx :load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (-> db
                       (update :replies (fnil conj []) reply))}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/x")}
                      :decode  :json}]]})))
        ;; First dispatch — interceptor fires.
        (rf/dispatch-sync [:load])
        (await-reply! #(= 1 (count (:replies %))) 5000)
        (is (= "Bearer A" @seen-auth) "first request carried the auth header")
        ;; Clear + dispatch again.
        (reset! seen-auth nil)
        (rf/clear-http-interceptor :auth-header)
        (rf/dispatch-sync [:load])
        (await-reply! #(= 2 (count (:replies %))) 5000)
        (is (nil? @seen-auth)
            "after clear, the second request did NOT carry the auth header")
        (finally (stop-server! srv))))))

;; ---- 6. re-registering an id replaces in place ---------------------------

(deftest re-registering-id-replaces-slot
  (testing "re-registering an id swaps the :before in the existing position; no duplicates"
    (let [order (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (rf/reg-http-interceptor
          {:id :a :before (fn [ctx] (swap! order conj :a-v1) ctx)})
        (rf/reg-http-interceptor
          {:id :b :before (fn [ctx] (swap! order conj :b) ctx)})
        ;; Replace :a — should keep its position (first), not append.
        (rf/reg-http-interceptor
          {:id :a :before (fn [ctx] (swap! order conj :a-v2) ctx)})
        (rf/reg-event-fx :load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/x")}
                    :decode  :json
                    :on-success nil}]]}))
        (rf/dispatch-sync [:load])
        (Thread/sleep 200)
        (is (= [:a-v2 :b] @order)
            "replaced :a's :before fired in :a's original position; no duplicate")
        (finally (stop-server! srv))))))

;; ---- 7. invalid interceptor shape raises ---------------------------------

(deftest invalid-interceptor-shape-raises
  (testing "reg-http-interceptor with a non-map / missing :id / non-fn :before throws :rf.error/http-bad-interceptor"
    (let [thrown (try (rf/reg-http-interceptor {:id :no-before})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.getMessage thrown))))
    (let [thrown (try (rf/reg-http-interceptor {:before (fn [c] c)})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.getMessage thrown))))
    (let [thrown (try (rf/reg-http-interceptor {:id :x :before "not-a-fn"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.getMessage thrown))))))
