(ns re-frame.http-privacy-integration-test
  "Integration tests for Spec 014 §Privacy (rf2-bma05) — end-to-end
  HTTP-cascade trace emission honouring the `:sensitive?` contract.

  Exercises the real :rf.http/managed dispatch path against an in-process
  HTTP server and asserts the emitted trace events on the trace bus are
  correctly redacted / stamped per the per-call, per-request, and
  handler-meta sensitivity sources."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.http-managed :as http-managed]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP / machine / routing fxs now require a carried
  ;; frame stamp. This suite exercises the ambient dispatch path against
  ;; a single conventional app frame, so register `:rf/default` explicitly
  ;; and pin it as the established scope for the whole body via with-frame.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http-managed :reload)
  ((requiring-resolve 're-frame.machines/reset-timers!))
  (http-managed/clear-all-in-flight!)
  ;; rf2-ppkh3v — app-specific carriers are FRAME policy now (EP-0015 §3);
  ;; the process-global clear-* fixtures are gone. Frame-extension cases
  ;; reg-frame their carriers and registrar/clear-all! resets between tests.
  (trace/clear-listeners!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- a tiny in-process HTTP server ----------------------------------------

(defn- start-server! [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx (reify HttpHandler (handle [_ exchange] (handler exchange))))
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

(defn- wait-for!
  "Thin alias over `test-support/poll-until` (rf2-fun38) — preserves
  the per-file arity (`pred`, `timeout-ms`)."
  [pred timeout-ms]
  (test-support/poll-until pred {:timeout-ms timeout-ms
                                 :label "http-privacy wait-for"}))

(defn- find-header
  "Case-insensitive lookup against a possibly mixed-case header map. The
  JDK normalises header casing differently from what the server set, so
  tests cannot rely on the exact spelling."
  [headers-map header-name]
  (let [lc (str/lower-case header-name)]
    (some (fn [[k v]]
            (when (= lc (str/lower-case (str k)))
              v))
          headers-map)))

;; ---- 1. Sensitive handler stamps :sensitive? on the failure trace event ---

;; ---- 1. (removed) handler-meta :sensitive? annotation no longer exists ----
;;
;; The original `sensitive-handler-stamps-trace-event-on-5xx` test pinned the
;; behaviour where handler registration metadata `:sensitive? true` propagated
;; to HTTP trace events. That annotation has been removed in favour of
;; path-marked classification + per-call `:sensitive?` on the args map (the
;; latter is covered by the test below).

;; ---- 2. Per-call :sensitive? on the request --------------------------------

(deftest per-call-sensitive-flag-takes-effect
  (testing "per-call :sensitive? on the args map redacts even when the
            handler is not declared sensitive"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "user-private-record")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :api/fetch
          (fn [_ [_ _msg]]
            ;; Handler itself is NOT sensitive; the per-call flag opts in.
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/data")}
                    :sensitive? true
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))]
          (is (or (true? (:sensitive? ev))
                  (true? (get-in ev [:tags :sensitive?]))))
          (is (= :rf/redacted (get-in ev [:tags :body]))))
        (finally
          (stop-server! srv))))))

;; ---- 3. Non-sensitive request preserves body -------------------------------

(deftest non-sensitive-request-preserves-body
  (testing "an ordinary handler with no :sensitive? flag emits the response
            body verbatim"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "ordinary error text")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :api/fetch
          (fn [_ [_ _msg]]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/data")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))]
          (is (and (nil? (:sensitive? ev))
                   (nil? (get-in ev [:tags :sensitive?])))
              "no :sensitive? stamp when neither handler nor call opts in")
          (is (= "ordinary error text" (get-in ev [:tags :body]))
              "body rides verbatim when not sensitive"))
        (finally
          (stop-server! srv))))))

;; ---- 4. Headers in the failure tags are always denylist-redacted -----------

(deftest sensitive-headers-redacted-in-failure-tags
  (testing "headers in the failure tags are denylist-redacted regardless
            of whether the request was declared :sensitive?"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (-> ex .getResponseHeaders (.set "Set-Cookie" "sid=secret"))
                  (-> ex .getResponseHeaders (.set "X-API-Key"  "k-abcd"))
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/x")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))
              headers (get-in ev [:tags :headers])]
          (is (= :rf/redacted (find-header headers "Set-Cookie"))
              "Set-Cookie was denylist-redacted (case-insensitive lookup)")
          (is (= :rf/redacted (find-header headers "X-API-Key"))
              "X-API-Key was denylist-redacted (case-insensitive lookup)"))
        (finally
          (stop-server! srv))))))

;; ---- 5. Frame-local carrier denylist applies (EP-0015 §3) ------------------

(deftest frame-carrier-redacts-custom-header
  (testing "a frame-local :sensitive {:http {:headers [..]}} carrier (EP-0015 §3)
            extends header redaction to app-defined names"
    ;; rf2-ppkh3v — re-register the operating frame with the frame-local
    ;; header carrier; the redactor unions it onto the immutable defaults
    ;; for emits from this frame.
    (rf/reg-frame :rf/default
      {:sensitive {:http {:headers ["X-Honeycomb-Team"]}}})
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (-> ex .getResponseHeaders (.set "X-Honeycomb-Team" "hc-token"))
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/x")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))
              headers (get-in ev [:tags :headers])]
          (is (= :rf/redacted (find-header headers "X-Honeycomb-Team"))
              "app-declared sensitive header was redacted"))
        (finally
          (stop-server! srv))))))

;; ---- 6. URL query-string denylist applies on failure trace events (rf2-2p8wr) -----

(deftest sensitive-query-param-redacted-in-failure-url
  (testing "a denylisted query-string param (api_key) has its value redacted
            in the failure trace event's URL even when the handler is not
            declared sensitive — the param name itself is the signal"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/x?api_key=SECRET&page=2")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))
              url (get-in ev [:tags :url])]
          (is (string? url))
          (is (str/includes? url "api_key=:rf/redacted")
              "denylisted api_key value redacted in URL")
          (is (str/includes? url "page=2")
              "non-denylisted page param preserved")
          (is (or (true? (:sensitive? ev))
                  (true? (get-in ev [:tags :sensitive?])))
              "denylist hit stamps :sensitive? on the trace event"))
        (finally
          (stop-server! srv))))))

;; ---- 7. Per-call sensitive request scrubs ALL URL query params (rf2-2p8wr) -

(deftest sensitive-request-redacts-all-url-query-params
  (testing "when the request is per-call :sensitive?, ALL query-string
            params (denylisted or not) are scrubbed in the failure trace
            event's URL — the broader rule (rf2-2p8wr)"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :auth/login
          {:doc "Login op (handler-meta :sensitive? annotation removed)."}
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/x?user_id=42&page=2")}
                    :sensitive? true
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:auth/login])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))
              url (get-in ev [:tags :url])]
          (is (string? url))
          (is (str/includes? url "user_id=:rf/redacted")
              "every param value scrubbed when sensitive — user_id")
          (is (str/includes? url "page=:rf/redacted")
              "every param value scrubbed when sensitive — page"))
        (finally
          (stop-server! srv))))))

;; ---- 8. Frame-local query-param carrier applies on failure URL (EP-0015 §3) -----

(deftest frame-carrier-query-param-redacts-failure-url
  (testing "a frame-local :sensitive {:http {:query-params [..]}} carrier
            (EP-0015 §3) extends URL redaction to app-defined params"
    ;; rf2-ppkh3v — re-register the operating frame with the frame-local
    ;; query-param carrier.
    (rf/reg-frame :rf/default
      {:sensitive {:http {:query-params ["shop_token"]}}})
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/x?shop_token=abc&page=2")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))
              url (get-in ev [:tags :url])]
          (is (string? url))
          (is (str/includes? url "shop_token=:rf/redacted")
              "app-declared sensitive query-param was redacted")
          (is (str/includes? url "page=2")
              "non-denylisted page param preserved"))
        (finally
          (stop-server! srv))))))

;; ---- 9. Response-body classification via the :decode schema (EP-0015 §8) ---

(deftest response-body-decode-schema-sensitive-slot-redacted-in-replied-trace
  (testing "rf2-ppkh3v — a 2xx response body's :decode-schema-marked sensitive
            slot is redacted in the :rf.http/replied trace value EVEN when the
            request is NOT declared per-call :sensitive? (the login/token case)"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json"
                                   "{\"token\":\"bearer-secret\",\"user-id\":42}")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        ;; The :decode schema is the owner's declaration: [:token] is
        ;; sensitive, [:user-id] is not. No per-call :sensitive? flag.
        (rf/reg-event-fx :auth/login
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/login")}
                    :decode  [:map
                              [:token {:sensitive? true} :string]
                              [:user-id :int]]
                    :on-success [:auth/ok]}]]}))
        (rf/reg-event-fx :auth/ok (fn [_ _] {}))

        (rf/dispatch-sync [:auth/login])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/replied (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/replied (:operation %)) @captured))
              v  (get-in ev [:tags :value])]
          (is (some? v) "the replied trace carries the decoded value")
          (is (= :rf/redacted (:token v))
              "schema-:sensitive? body slot redacted (no per-call flag needed)")
          (is (= 42 (:user-id v))
              "non-sensitive body slot rides verbatim"))
        (finally
          (stop-server! srv))))))

(deftest response-body-whole-body-sensitive-decode-schema-redacts-all
  (testing "rf2-ppkh3v — a root-level :sensitive? :decode schema (opaque-token
            response) redacts the WHOLE body in the replied trace"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json" "\"opaque-token-value\"")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        (rf/reg-event-fx :auth/refresh
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/refresh")}
                    :decode  [:string {:sensitive? true}]
                    :on-success [:auth/ok]}]]}))
        (rf/reg-event-fx :auth/ok (fn [_ _] {}))

        (rf/dispatch-sync [:auth/refresh])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/replied (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/replied (:operation %)) @captured))
              v  (get-in ev [:tags :value])]
          (is (= :rf/redacted v) "whole opaque-token body redacted"))
        (finally
          (stop-server! srv))))))
