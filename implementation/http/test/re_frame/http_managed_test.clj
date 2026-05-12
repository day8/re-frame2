(ns re-frame.http-managed-test
  "JVM smoke tests for Spec 014 — `:rf.http/managed`.

  Covers the canned-stub fxs (no real network IO needed for the stubs)
  AND, where in-process HTTP is convenient, the real
  `java.net.http.HttpClient`-backed transport against a tiny
  com.sun.net.httpserver test server.

  Per Spec 014 §Implementation status — JVM transport is part of the
  CLJS reference implementation's claim."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.http-managed :as http-managed]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
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
  ((requiring-resolve 're-frame.machines/reset-counters!))
  (http-managed/clear-all-in-flight!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- a tiny in-process HTTP server ----------------------------------------
;;
;; com.sun.net.httpserver ships with the JDK; spinning up one per test
;; is fast enough (~5ms) and gives us a real socket to point HttpClient at.

(defn- start-server!
  "Start an HttpServer with the given handler. Returns a {:server ::server :port N} map.
  Stop with (.stop server 0)."
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

;; ---- helpers --------------------------------------------------------------

(defn- await-reply!
  "Wait up to timeout-ms for `(pred (rf/get-frame-db :rf/default))` to be
  true. Returns the final db on success, throws on timeout."
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

;; ---- 1. canned-success: round-trip default reply addressing ---------------

(deftest canned-success-default-reply-addressing
  (testing "the canned-success stub dispatches a default reply (originating event-id with :rf/reply)"
    (rf/reg-event-fx :article/load
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          (case (:kind reply)
            :success {:db (assoc-in db [:article :data] (:value reply))}
            :failure {:db (assoc-in db [:article :error] (:failure reply))})
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles/hello"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:article/load {:slug "hello"}]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    ;; Stubs synthesise replies via the router; await the dispatch.
    (let [db (await-reply! #(some? (get-in % [:article :data])))]
      (is (= {:stubbed true} (get-in db [:article :data]))))))

;; ---- 2. canned-failure: explicit on-failure addressing ---------------------

(deftest canned-failure-explicit-on-failure
  (testing "explicit :on-failure routes the failure reply to the named handler"
    (rf/reg-event-fx :auth/login
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request   {:method :post :url "/auth/login"}
                :on-failure [:auth/login-error]}]]}))
    (rf/reg-event-db :auth/login-error
      (fn [db [_ payload]]
        (assoc db :auth-error payload)))
    (rf/dispatch-sync [:auth/login]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (await-reply! #(some? (:auth-error %)))]
      (is (= :failure (get-in db [:auth-error :kind])))
      (is (= :rf.http/transport (get-in db [:auth-error :failure :kind]))))))

;; ---- 3. silenced reply (on-success nil) -----------------------------------

(deftest silenced-reply-on-success-nil
  (testing "explicit :on-success nil swallows the reply silently"
    (let [seen (atom 0)]
      (rf/reg-event-fx :ping
        (fn [_ _]
          (swap! seen inc)
          {:fx [[:rf.http/managed
                 {:request    {:url "/ping"}
                  :on-success nil}]]}))
      (rf/dispatch-sync [:ping]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
      ;; Wait long enough for any reply dispatch to settle.
      (Thread/sleep 100)
      ;; Only the initial dispatch fired :ping; no reply.
      (is (= 1 @seen)))))

;; ---- 4. real JVM transport: GET success -----------------------------------

(deftest jvm-real-get-success
  (testing "java.net.http.HttpClient transport — GET, JSON decode, default reply"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json"
                               "{\"article\":{\"title\":\"hello\",\"id\":42}}")))]
      (try
        (rf/reg-event-fx :article/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              (case (:kind reply)
                :success {:db (assoc db :article (:value reply))}
                :failure {:db (assoc db :error  (:failure reply))})
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/articles/hello")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:article/load {}])
        (let [db (await-reply! #(some? (:article %)) 5000)]
          (is (= "hello" (get-in db [:article :article :title]))))
        (finally (stop-server! srv))))))

;; ---- 5. real JVM transport: 4xx routes through failure --------------------

(deftest jvm-real-http-4xx
  (testing "non-2xx 4xx response classifies as :rf.http/http-4xx"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 404 "application/json" "{\"error\":\"not-found\"}")))]
      (try
        (rf/reg-event-fx :article/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/missing")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:article/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/http-4xx (get-in db [:reply :failure :kind])))
          (is (= 404 (get-in db [:reply :failure :status]))))
        (finally (stop-server! srv))))))

;; ---- 5b. HTML 404 with :decode :json — status check precedes decode ------
;;
;; Regression guard for rf2-lokk: a 4xx response whose body is HTML (or any
;; shape that would FAIL :json decode) MUST classify as :rf.http/http-4xx,
;; NOT :rf.http/decode-failure. Per Spec 014 §Failure categories, status
;; classification runs BEFORE decode — decode never fires on a non-2xx
;; response. The :body tag carries the raw response body-text.

(deftest jvm-html-404-with-json-decode-routes-to-http-4xx
  (testing "HTML 4xx response with :decode :json classifies as :rf.http/http-4xx (not :rf.http/decode-failure)"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 404 "text/html"
                               "<!doctype html><html><body><h1>Not Found</h1></body></html>")))]
      (try
        (rf/reg-event-fx :page/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/missing")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:page/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/http-4xx (:kind failure))
              "status classification precedes decode: HTML body must NOT trigger :rf.http/decode-failure")
          (is (= 404 (:status failure)))
          ;; The :body is the RAW response body, not a decoded value.
          (is (string? (:body failure))
              ":body carries the raw response text on 4xx (decode skipped)")
          (is (clojure.string/includes? (:body failure) "Not Found")))
        (finally (stop-server! srv))))))

;; ---- 5c. throwing decoder on 200 still routes to :rf.http/decode-failure -
;;
;; The complement of 5b: a 2xx response whose decode pipeline throws DOES land
;; as :rf.http/decode-failure, since decode runs on success-eligible responses.
;; We use a custom decoder fn that throws — the JVM's :json fallback parser is
;; lenient (returns the raw string when malformed) so a thrown decoder is the
;; portable way to exercise the decode-failure path.

(deftest jvm-throwing-decoder-on-200-routes-to-decode-failure
  (testing "200 response whose decode pipeline throws classifies as :rf.http/decode-failure"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event-fx :page/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/ok")}
                      :decode  (fn [_text _headers]
                                 (throw (ex-info "boom" {})))}]]})))
        (rf/dispatch-sync [:page/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/decode-failure (:kind failure))
              "decode runs on 2xx; a thrown decoder surfaces as :rf.http/decode-failure"))
        (finally (stop-server! srv))))))

;; ---- 6. retry exhaustion --------------------------------------------------

(deftest jvm-retry-exhaustion
  (testing ":retry exhausts after :max-attempts, dispatching a single :on-failure"
    (let [hits (atom 0)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (swap! hits inc)
              (write-response! ex 500 "application/json" "{\"err\":true}")))]
      (try
        (rf/reg-event-fx :flaky/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/flaky")}
                      :decode  :json
                      :retry   {:on           #{:rf.http/http-5xx}
                                :max-attempts 3
                                :backoff      {:base-ms 5 :factor 1 :max-ms 10}}}]]})))
        (rf/dispatch-sync [:flaky/load])
        (let [db (await-reply! #(some? (:reply %)) 8000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/http-5xx (get-in db [:reply :failure :kind])))
          ;; The server saw all 3 attempts.
          (is (= 3 @hits)))
        (finally (stop-server! srv))))))

;; ---- 7. retry recover -----------------------------------------------------

(deftest jvm-retry-recover
  (testing ":retry recovers when an intermediate attempt succeeds"
    (let [hits (atom 0)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (let [n (swap! hits inc)]
                (if (= 1 n)
                  (write-response! ex 500 "application/json" "{\"err\":true}")
                  (write-response! ex 200 "application/json" "{\"ok\":true}")))))]
      (try
        (rf/reg-event-fx :recover/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/recover")}
                      :decode  :json
                      :retry   {:on           #{:rf.http/http-5xx}
                                :max-attempts 3
                                :backoff      {:base-ms 5 :factor 1 :max-ms 10}}}]]})))
        (rf/dispatch-sync [:recover/load])
        (let [db (await-reply! #(some? (:reply %)) 8000)]
          (is (= :success (get-in db [:reply :kind])))
          (is (= {:ok true} (get-in db [:reply :value])))
          (is (= 2 @hits)))
        (finally (stop-server! srv))))))

;; ---- 8. transport failure --------------------------------------------------

(deftest jvm-transport-failure
  (testing "connection-refused classifies as :rf.http/transport"
    (rf/reg-event-fx :load
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :reply reply)}
          {:fx [[:rf.http/managed
                 ;; Pick a port we expect to be closed.
                 {:request {:url "http://127.0.0.1:1/never"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:load])
    (let [db (await-reply! #(some? (:reply %)) 5000)]
      (is (= :failure (get-in db [:reply :kind])))
      (is (= :rf.http/transport (get-in db [:reply :failure :kind]))))))

;; ---- 9. abort by request-id -----------------------------------------------

(deftest jvm-abort-by-request-id
  (testing ":rf.http/managed-abort cancels an in-flight request by id"
    (let [latch (CountDownLatch. 1)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Block until the test releases the latch.
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"too\":\"late\"}")))]
      (try
        (rf/reg-event-fx :slow/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                      :request-id :slow
                      :decode     :json}]]})))
        (rf/reg-event-fx :slow/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :slow]]}))
        (rf/dispatch-sync [:slow/load])
        (Thread/sleep 50)
        (rf/dispatch-sync [:slow/abort])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/aborted (get-in db [:reply :failure :kind]))))
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- 10. thunk body -------------------------------------------------------

(deftest jvm-thunk-body
  (testing ":body as a thunk is invoked at request-send time"
    (let [thunk-calls (atom 0)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"echoed\":true}")))]
      (try
        (rf/reg-event-fx :upload
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:method :post
                                :url    (str "http://127.0.0.1:" port "/upload")
                                :body   (fn []
                                          (swap! thunk-calls inc)
                                          {:payload :ok})
                                :request-content-type :json}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:upload])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :success (get-in db [:reply :kind])))
          (is (= 1 @thunk-calls)))
        (finally (stop-server! srv))))))

;; ---- 11. with-managed-request-stubs helper --------------------------------

(deftest with-managed-request-stubs-helper
  (testing "with-managed-request-stubs routes :method+:url to the configured reply"
    (rf/reg-event-fx :articles/list
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :result reply)}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs
      {[:get "/articles"] {:reply {:ok [:hello :world]}}}
      (rf/dispatch-sync [:articles/list]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
      (let [db (await-reply! #(some? (:result %)) 2000)]
        (is (= :success (get-in db [:result :kind])))
        (is (= [:hello :world] (get-in db [:result :value])))))))

;; ---- 12. decode reflection metadata ---------------------------------------

(deftest decode-reflection-metadata
  (testing ":rf.http/decode-schemas declared on the handler is queryable via handler-meta"
    (rf/reg-event-fx :article/load
      {:doc                    "Load an article."
       :rf.http/decode-schemas [::ArticleResponse]}
      (fn [_ _] {}))
    (let [m (rf/handler-meta :event :article/load)]
      (is (= [::ArticleResponse] (:rf.http/decode-schemas m))))))

;; ---- actor-in-flight-snapshot shape contract (rf2-kyl7) -------------------
;;
;; Per rf2-kyl7: `actor-in-flight-snapshot` and `in-flight-snapshot`
;; are read by assertions across http_actor_destroy_cancellation_test
;; and http_managed_machine_test, but no test PINS THE SHAPE of the
;; snapshot — which keys, which values. A wire-protocol regression
;; (e.g. someone changing the value to a single handle instead of a
;; vector of handles) would slip through every existing assertion.
;;
;; Source: http_managed.cljc:177-189. Storage is:
;;   `actor-in-flight` : actor-id → vector of handle maps
;;   `in-flight`       : request-id → single handle map
;; Each handle map carries `:abort-fn`, `:url`, plus the framework
;; stamps `:request-id` and `:actor-id` (when applicable).

(defn- await-condition!
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) :done
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "timeout awaiting condition" {}))
        :else (do (Thread/sleep 25) (recur))))))

(deftest actor-in-flight-snapshot-shape
  (testing "actor-in-flight-snapshot is a map keyed by actor-id, value is a
            vector of handle maps each carrying :abort-fn / :url / :request-id
            / :actor-id. in-flight-snapshot is keyed by request-id."
    (let [latch (CountDownLatch. 1)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Block — both requests must remain in-flight while the test
              ;; reads the snapshots.
              (.await latch 10 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{}")))]
      (try
        ;; Issue two requests from inside a spawned actor so both land
        ;; in the actor-in-flight index. The actor pattern mirrors
        ;; http_actor_destroy_cancellation_test (2).
        (require 're-frame.machines :reload)
        (rf/reg-machine :kyl7/worker
          {:initial :idle
           :data    {:port port}
           :actions
           {:fire-two
            (fn [data _]
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" (:port data) "/a")}
                      :request-id :kyl7/a
                      :decode     :json
                      :on-success nil
                      :on-failure nil}]
                    [:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" (:port data) "/b")}
                      :request-id :kyl7/b
                      :decode     :json
                      :on-success nil
                      :on-failure nil}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-two}}})
        (rf/reg-machine :kyl7/sup
          {:initial :idle
           :states
           {:idle    {:on {:start :working}}
            :working {:invoke {:machine-id :kyl7/worker
                               :start      [:start]}}}})
        (rf/dispatch-sync [:kyl7/sup [:start]])

        ;; Wait for both requests to be in-flight under the same actor.
        (await-condition!
          #(let [snap (http-managed/actor-in-flight-snapshot)]
             (and (= 1 (count snap))
                  (= 2 (count (val (first snap))))))
          5000)

        ;; ---- actor-in-flight-snapshot shape ----
        (let [actor-snap (http-managed/actor-in-flight-snapshot)]
          (is (map? actor-snap)
              "actor-in-flight-snapshot returns a map")
          (is (= 1 (count actor-snap))
              "one actor key — the spawned :kyl7/worker child")
          (let [[actor-id handles] (first actor-snap)]
            (is (keyword? actor-id)
                "actor-id is a keyword (the spawned actor's address)")
            (is (= :kyl7/worker#1 actor-id)
                "actor-id is the deterministic spawn id of the child")
            (is (vector? handles)
                "value under each actor-id is a vector (multiple in-flight requests
                 from the same actor accumulate as siblings)")
            (is (= 2 (count handles))
                "two in-flight requests from this actor")
            (doseq [h handles]
              (is (map? h)
                  "each handle is a map")
              (is (fn? (:abort-fn h))
                  ":abort-fn is the no-arg cancellation fn")
              (is (string? (:url h))
                  ":url stamps the resolved URL for diagnostic visibility")
              (is (= actor-id (:actor-id h))
                  ":actor-id stamped on the handle matches its index key")
              (is (#{:kyl7/a :kyl7/b} (:request-id h))
                  ":request-id stamped on the handle matches the user-supplied id"))))

        ;; ---- in-flight-snapshot shape (request-id-keyed) ----
        (let [req-snap (http-managed/in-flight-snapshot)]
          (is (map? req-snap)
              "in-flight-snapshot returns a map")
          (is (= 2 (count req-snap))
              "two request-id keys — one per in-flight request")
          (is (= #{:kyl7/a :kyl7/b} (set (keys req-snap)))
              "request-id keys match the user-supplied :request-id values")
          (doseq [[req-id handle] req-snap]
            (is (map? handle)
                "each value is a SINGLE handle map (NOT a vector — unlike actor index)")
            (is (= req-id (:request-id handle))
                ":request-id on the handle matches its index key")
            (is (fn? (:abort-fn handle))
                ":abort-fn is the cancellation fn (same as actor-index handle)")))

        ;; Release so the JDK sockets close cleanly.
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- 13. timeout failure category -----------------------------------------

(deftest jvm-timeout-failure
  (testing "per-attempt timeout fires :rf.http/timeout"
    (let [latch (CountDownLatch. 1)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (.await latch 10 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"too\":\"late\"}")))]
      (try
        (rf/reg-event-fx :slow/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                      :timeout-ms 80
                      :decode     :json}]]})))
        (rf/dispatch-sync [:slow/load])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/timeout (get-in db [:reply :failure :kind]))))
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- 14. supersede on same :request-id (rf2-lxd3) -------------------------
;;
;; Per rf2-lxd3 decision A: when a fresh request supersedes a prior one
;; with the same `:request-id`, the prior request's `:on-failure` reply
;; is NOT dispatched (semantic = the new request replaces the old one,
;; debounce-search mental model). The supersede event still emits to
;; the trace bus (`:rf.http/aborted` with `:reason :request-id-superseded`);
;; consumers wanting abort telemetry subscribe via `register-trace-cb!`.

(deftest jvm-supersede-does-not-fire-on-failure
  (testing "rf2-lxd3 — superseding a request with the same :request-id MUST NOT
            fire the prior request's :on-failure. The :on-success is silenced
            (nil) on both requests so the test isolates failure-reply behaviour
            from the JVM transport's natural-completion path."
    (let [latch         (CountDownLatch. 1)
          a-failed?     (atom false)
          b-success?    (atom false)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Block long enough for the second dispatch to supersede the
              ;; first BEFORE the server responds.
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event-fx :search/run
          (fn [_ [_ q]]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q?" q)}
                    :request-id :search
                    :decode     :json
                    ;; Silence success on the prior request — only the
                    ;; supersede-driven :on-failure dispatch is under test.
                    :on-success nil
                    :on-failure [:search/a-failed]}]]}))
        (rf/reg-event-fx :search/run-superseding
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q?fresh")}
                    :request-id :search
                    :decode     :json
                    :on-failure nil
                    :on-success [:search/b-ok]}]]}))
        (rf/reg-event-db :search/a-failed (fn [db _] (reset! a-failed? true) db))
        (rf/reg-event-db :search/b-ok     (fn [db _] (reset! b-success? true) db))

        (rf/dispatch-sync [:search/run "stale"])
        ;; Let the first request reach in-flight.
        (await-condition!
          #(seq (http-managed/in-flight-snapshot))
          2000)
        ;; Fire the superseding request — same :request-id.
        (rf/dispatch-sync [:search/run-superseding])
        ;; Release the server so the second request can complete.
        (.countDown latch)
        ;; Wait for the second request's success reply.
        (await-condition! #(true? @b-success?) 5000)
        ;; The PRIOR request's :on-failure (:search/a-failed) MUST NOT
        ;; have fired. Allow extra settle time to rule out a delayed
        ;; dispatch from either the abort path or the natural-completion
        ;; path (which is :success, silenced by :on-success nil).
        (Thread/sleep 100)
        (is (false? @a-failed?)
            "the superseded request's :on-failure must NOT fire (rf2-lxd3 fix)")
        (is (true? @b-success?)
            "the superseding request's :on-success DOES fire")
        (finally (stop-server! srv))))))

(deftest jvm-supersede-still-emits-trace-event
  (testing "rf2-lxd3 — supersede still emits :rf.http/aborted trace event with
            :reason :request-id-superseded so register-trace-cb! consumers
            keep visibility"
    (let [latch    (CountDownLatch. 1)
          events   (atom [])
          cb-id    ::lxd3-trace
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (trace/register-trace-cb! cb-id
                                  (fn [ev]
                                    (when (= :rf.http/aborted (:operation ev))
                                      (swap! events conj ev))))
        (rf/reg-event-fx :search/run
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q1")}
                    :request-id :search
                    :decode     :json}]]}))
        (rf/reg-event-fx :search/run-superseding
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q2")}
                    :request-id :search
                    :decode     :json}]]}))

        (rf/dispatch-sync [:search/run])
        (await-condition!
          #(seq (http-managed/in-flight-snapshot))
          2000)
        (rf/dispatch-sync [:search/run-superseding])
        (await-condition! #(seq @events) 2000)

        (let [ev (first @events)
              tags (:tags ev)]
          (is (= :rf.http/aborted (:operation ev))
              "supersede emits :rf.http/aborted trace event")
          (is (= :request-id-superseded (:reason tags))
              ":reason :request-id-superseded distinguishes supersede from :user / :actor-destroyed")
          (is (= :search (:request-id tags))
              ":request-id rides on the trace event"))

        (.countDown latch)
        (finally
          (trace/remove-trace-cb! cb-id)
          (stop-server! srv))))))

(deftest jvm-non-superseded-abort-still-fires-reply
  (testing "rf2-lxd3 regression guard — a non-supersede abort (manual
            :rf.http/managed-abort) STILL fires :on-failure as before.
            Only :reason :request-id-superseded suppresses the reply."
    (let [latch        (CountDownLatch. 1)
          reply-fired? (atom false)
          reply-data   (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (rf/reg-event-fx :slow/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                    :request-id :slow
                    :decode     :json
                    :on-failure [:slow/failed]}]]}))
        (rf/reg-event-fx :slow/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :slow]]}))
        (rf/reg-event-db :slow/failed
          (fn [db [_ payload]]
            (reset! reply-fired? true)
            (reset! reply-data payload)
            db))

        (rf/dispatch-sync [:slow/load])
        (await-condition!
          #(seq (http-managed/in-flight-snapshot))
          2000)
        ;; User-initiated abort — the abort fn passes `:user` as the reason.
        (rf/dispatch-sync [:slow/abort])
        (await-condition! #(true? @reply-fired?) 2000)

        (is (true? @reply-fired?)
            "non-supersede abort STILL dispatches :on-failure")
        ;; Per build-reply-event: explicit :on-failure [:slow/failed] appends
        ;; the reply payload as the last arg — the handler receives the
        ;; payload directly (NOT wrapped under :rf/reply).
        (let [reply @reply-data]
          (is (= :failure (:kind reply))
              "the reply is a :failure reply")
          (is (= :rf.http/aborted (-> reply :failure :kind))
              "failure kind is :rf.http/aborted")
          (is (not= :request-id-superseded (-> reply :failure :reason))
              ":reason is NOT :request-id-superseded (this is the regression guard)"))

        (.countDown latch)
        (finally (stop-server! srv))))))
