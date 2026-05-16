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
            [re-frame.http-privacy-headers :as privacy-headers]
            [re-frame.http-url :as http-url]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
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
  (require 're-frame.machines :reload)
  (require 're-frame.http-managed :reload)
  ((requiring-resolve 're-frame.machines/reset-timers!))
  (http-managed/clear-all-in-flight!)
  (privacy-headers/clear-sensitive-headers!)
  (http-url/clear-sensitive-query-params!)
  (trace/clear-trace-cbs!)
  (t))

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

(deftest sensitive-handler-stamps-trace-event-on-5xx
  (testing "a 5xx failure from a :sensitive? handler emits a trace event
            stamped :sensitive? with the body redacted"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "internal-secret-PII")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-trace-cb! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :auth/sign-in
          {:doc "Sensitive sign-in operation."
           :sensitive? true}
          (fn [_ [_ _msg]]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/login")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:auth/sign-in {:user "ada" :pass "shhh"}])

        (let [_ (wait-for!
                  (fn []
                    (some #(= :rf.http/http-5xx (:operation %)) @captured))
                  3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))]
          (is (or (true? (:sensitive? ev))
                  (true? (get-in ev [:tags :sensitive?])))
              "the trace event carries :sensitive? — at the top level (core has hoisted from tags) or under tags (pre-hoist)")
          (is (= :rf/redacted (get-in ev [:tags :body]))
              "the response body is redacted before reaching the trace surface"))
        (finally
          (stop-server! srv))))))

;; ---- 2. Per-call :sensitive? on a non-sensitive handler -------------------

(deftest per-call-sensitive-flag-takes-effect
  (testing "per-call :sensitive? on the args map redacts even when the
            handler is not declared sensitive"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "user-private-record")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-trace-cb! :test/capture
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
        (trace/register-trace-cb! :test/capture
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
        (trace/register-trace-cb! :test/capture
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

;; ---- 5. App-extended denylist applies --------------------------------------

(deftest app-extended-denylist-redacts-custom-header
  (testing "declare-sensitive-header! extends redaction to app-defined names"
    (privacy-headers/declare-sensitive-header! "X-Honeycomb-Team")
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (-> ex .getResponseHeaders (.set "X-Honeycomb-Team" "hc-token"))
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-trace-cb! :test/capture
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
        (trace/register-trace-cb! :test/capture
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

;; ---- 7. Sensitive handler scrubs ALL URL query params (rf2-2p8wr) ----------------

(deftest sensitive-handler-redacts-all-url-query-params
  (testing "when the originating handler is :sensitive?, ALL query-string
            params (denylisted or not) are scrubbed in the failure trace
            event's URL — the broader rule (rf2-2p8wr)"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-trace-cb! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event-fx :auth/login
          {:doc        "Sensitive login op."
           :sensitive? true}
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/x?user_id=42&page=2")}
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

;; ---- 8. App-extended query-param denylist applies on failure URL (rf2-2p8wr) -----

(deftest app-extended-query-param-denylist-redacts-failure-url
  (testing "declare-sensitive-query-param! extends URL redaction to app-defined params"
    (http-url/declare-sensitive-query-param! "shop_token")
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-trace-cb! :test/capture
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
