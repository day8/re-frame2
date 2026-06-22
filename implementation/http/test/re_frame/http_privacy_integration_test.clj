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
            [re-frame.http.managed :as http-managed]
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
  (require 're-frame.http.managed :reload)
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

        (rf/reg-event :api/fetch
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

        (rf/reg-event :api/fetch
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

        (rf/reg-event :api/fetch
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

;; ---- 5. Managed-HTTP carrier denylist applies (EP-0025 §HTTP carriers) ------

(deftest managed-carrier-redacts-custom-header
  (testing "a :rf.http/managed :carriers {:headers [..]} carrier (EP-0025)
            extends header redaction to app-defined names"
    ;; EP-0025 — re-register :rf.http/managed with the app's :carriers block;
    ;; the redactor unions it onto the immutable defaults at trace egress.
    (rf/reg-fx :rf.http/managed
      {:carriers {:headers ["X-Honeycomb-Team"]}}
      http-managed/managed-handler)
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (-> ex .getResponseHeaders (.set "X-Honeycomb-Team" "hc-token"))
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event :api/fetch
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

        (rf/reg-event :api/fetch
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

        (rf/reg-event :auth/login
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

;; ---- 8. Managed-HTTP query-param carrier applies on failure URL (EP-0025) ----

(deftest managed-carrier-query-param-redacts-failure-url
  (testing "a :rf.http/managed :carriers {:query-params [..]} carrier
            (EP-0025) extends URL redaction to app-defined params"
    ;; EP-0025 — re-register :rf.http/managed with the app's query-param carrier.
    (rf/reg-fx :rf.http/managed
      {:carriers {:query-params ["shop_token"]}}
      http-managed/managed-handler)
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 500 "text/plain" "boom")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))

        (rf/reg-event :api/fetch
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
        (rf/reg-event :auth/login
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/login")}
                    :decode  [:map
                              [:token {:sensitive? true} :string]
                              [:user-id :int]]
                    :on-success [:auth/ok]}]]}))
        (rf/reg-event :auth/ok (fn [_ _] {}))

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
        (rf/reg-event :auth/refresh
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/refresh")}
                    :decode  [:string {:sensitive? true}]
                    :on-success [:auth/ok]}]]}))
        (rf/reg-event :auth/ok (fn [_ _] {}))

        (rf/dispatch-sync [:auth/refresh])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/replied (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/replied (:operation %)) @captured))
              v  (get-in ev [:tags :value])]
          (is (= :rf/redacted v) "whole opaque-token body redacted"))
        (finally
          (stop-server! srv))))))

(deftest response-body-large-slot-elided-in-replied-trace
  (testing "rf2-jhyccs — a 2xx response body's :decode-schema-marked :large?
            slot is elided to the :rf.size/large-elided marker in the
            :rf.http/replied trace value (the per-slot large axis, wired
            alongside :sensitive?)"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json"
                                   "{\"blob\":\"PAYLOAD-PAYLOAD-PAYLOAD\",\"user-id\":7}")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        (rf/reg-event :api/big
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/big")}
                    :decode  [:map
                              [:blob {:large? true} :string]
                              [:user-id :int]]
                    :on-success [:api/ok]}]]}))
        (rf/reg-event :api/ok (fn [_ _] {}))

        (rf/dispatch-sync [:api/big])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/replied (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/replied (:operation %)) @captured))
              v  (get-in ev [:tags :value])]
          (is (contains? (:blob v) :rf.size/large-elided)
              "schema-:large? body slot elided to the size marker")
          (is (= 7 (:user-id v))
              "non-large body slot rides verbatim"))
        (finally
          (stop-server! srv))))))

;; ---- 10. Off-box disposition stamp on the replied trace (rf2-t55hxg.6) -----

(deftest replied-trace-stamps-off-box-omit-for-unschematized-body
  (testing "rf2-t55hxg.6 — an UNSCHEMATIZED (:auto) :decode stamps
            :rf.http/off-box-body :omit on the :rf.http/replied trace so the
            off-box projector omits the body (the on-box :value still rides
            raw for the local operator — fail-closed is the OFF-BOX rule)"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json"
                                   "{\"opaque\":\"raw-token\"}")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        ;; No :decode ⇒ :auto ⇒ unschematized ⇒ off-box :omit.
        (rf/reg-event :api/opaque
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/opaque")}
                    :on-success [:api/ok]}]]}))
        (rf/reg-event :api/ok (fn [_ _] {}))

        (rf/dispatch-sync [:api/opaque])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/replied (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/replied (:operation %)) @captured))]
          (is (= :omit (get-in ev [:tags :rf.http/off-box-body]))
              "unschematized body stamped :omit for the off-box projector")
          (is (some? (get-in ev [:tags :value]))
              "the on-box :value still rides raw — the local operator sees
               their own process; the omission is the off-box boundary"))
        (finally
          (stop-server! srv))))))

(deftest replied-trace-stamps-off-box-classify-for-schema-body
  (testing "rf2-t55hxg.6 — a SCHEMA :decode stamps :rf.http/off-box-body
            :classify (the body rides the per-slot classified projection
            off-box)"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json"
                                   "{\"token\":\"bearer-secret\",\"user-id\":42}")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        (rf/reg-event :api/login
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:method :get
                              :url    (str "http://127.0.0.1:" port "/login")}
                    :decode  [:map
                              [:token {:sensitive? true} :string]
                              [:user-id :int]]
                    :on-success [:api/ok]}]]}))
        (rf/reg-event :api/ok (fn [_ _] {}))

        (rf/dispatch-sync [:api/login])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/replied (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/replied (:operation %)) @captured))]
          (is (= :classify (get-in ev [:tags :rf.http/off-box-body]))
              "schema body stamped :classify for the off-box projector"))
        (finally
          (stop-server! srv))))))

;; rf2-y1pgdl — the end-to-end emit→projector path for an OPAQUE keyword
;; registry-ref / compiled-schema `:decode` is locked at the unit altitude in
;; `http_privacy_body_test` (`off-box-disposition-omits-opaque-registry-ref`,
;; `off-box-classify-body-omits-opaque-registry-ref`): the emit site stamps
;; exactly `off-box-body-disposition` and the projector keys on that stamp, so
;; the disposition fn IS the path. An integration test here would need a
;; SUCCESSFULLY-DECODING opaque ref (a registered Malli registry-ref schema
;; resolvable by `malli.core/decode`), which is heavyweight and orthogonal to
;; the fail-closed stamp the fix changes. The schema-VECTOR `:classify` and
;; unschematized `:omit` end-to-end stamps are covered above.

;; ---- 11. Off-box disposition stamp on RAW error-response bodies (rf2-t55hxg.10) ----
;;
;; EP-0015 disposition 5 fail-OPEN gap closed: a raw 4xx/5xx response body
;; (`:body`) and a decode-failure raw text (`:body-text`) egress raw off-box
;; and were previously redacted ONLY when the call carried a per-call
;; `:sensitive?` flag. A raw error body is UNSCHEMATIZED by construction
;; (status classification runs BEFORE decode), so the emit site UNCONDITIONALLY
;; stamps `:rf.http/off-box-body :omit` (irrespective of the per-call flag) so
;; the off-box trace-events projector omits it. The on-box body still rides raw
;; for the local operator (the omission is the off-box boundary).

(deftest http-5xx-stamps-off-box-omit-on-raw-body-non-sensitive
  (testing "rf2-t55hxg.10 — a NON-per-call-sensitive 5xx whose raw response
            body echoes a token stamps :rf.http/off-box-body :omit on the
            :rf.http/http-5xx trace so the off-box projector omits :body
            (closing the fail-OPEN that left it raw off-box). The on-box :body
            still rides raw — the local operator sees their own process."
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  ;; The error body echoes the caller's bearer token — exactly
                  ;; the leak class disposition 5 fails closed against.
                  (write-response! ex 500 "text/plain"
                                   "error: token=bearer-abc123 rejected")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        ;; NO per-call :sensitive? flag — the disposition-5 fix must fire anyway.
        (rf/reg-event :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/data")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/http-5xx (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))]
          (is (= :omit (get-in ev [:tags :rf.http/off-box-body]))
              "raw 5xx body stamped :omit for the off-box projector (fail-closed)")
          (is (= "error: token=bearer-abc123 rejected" (get-in ev [:tags :body]))
              "the on-box :body still rides raw — the local operator sees it;
               the omission is the OFF-BOX boundary, enforced by the projector")
          (is (and (nil? (:sensitive? ev))
                   (nil? (get-in ev [:tags :sensitive?])))
              "no per-call :sensitive? was set — the :omit stamp is unconditional,
               not contingent on the per-call flag (the closed gap)"))
        (finally
          (stop-server! srv))))))

(deftest http-4xx-stamps-off-box-omit-on-raw-body
  (testing "rf2-t55hxg.10 — a 4xx raw body is likewise stamped :omit off-box"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 403 "text/plain" "forbidden: secret-ctx")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        (rf/reg-event :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/data")}
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/http-4xx (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/http-4xx (:operation %)) @captured))]
          (is (= :omit (get-in ev [:tags :rf.http/off-box-body]))
              "raw 4xx body stamped :omit for the off-box projector")
          (is (= "forbidden: secret-ctx" (get-in ev [:tags :body]))
              "on-box :body rides raw"))
        (finally
          (stop-server! srv))))))

(deftest decode-failure-stamps-off-box-omit-on-raw-body-text
  (testing "rf2-t55hxg.10 — a :rf.http/decode-failure (a 200 whose body fails
            the :decode) carries the raw text at :body-text; it is UNSCHEMATIZED
            by construction (the decode is what failed) so it is stamped :omit
            off-box, irrespective of the per-call flag"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  ;; 200 OK but the body is not valid JSON → decode-failure.
                  (write-response! ex 200 "application/json"
                                   "not-json: leaked-token=xyz")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        ;; :json decode of a non-JSON body throws → :rf.http/decode-failure.
        (rf/reg-event :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/data")}
                    :decode     :json
                    :on-failure nil}]]}))

        (rf/dispatch-sync [:api/fetch])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/decode-failure (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/decode-failure (:operation %)) @captured))]
          (is (= :omit (get-in ev [:tags :rf.http/off-box-body]))
              "raw decode-failure body-text stamped :omit for the off-box projector")
          (is (= "not-json: leaked-token=xyz" (get-in ev [:tags :body-text]))
              "on-box :body-text rides raw — the local operator inspects it"))
        (finally
          (stop-server! srv))))))

;; ---- 12. :accept {:failure ...} emits the accept-failure category trace ----
;;   + applies decoded-body privacy / off-box disposition (rf2-ltaihw)
;;
;; A successful 2xx decode whose `:accept` projects the decoded value to
;; `{:failure user-map}` is an `:rf.http/accept-failure` domain failure. The
;; bug: `finalise-success!`'s accept-`{:failure}` branch dispatched through
;; `dispatch-failure!` directly, which emits ONLY the canonical
;; `:rf.http/replied` envelope and bypasses `emit-and-dispatch-failure!` — so
;; the `:rf.http/accept-failure` failure-category trace (via `emit-error!`) was
;; NEVER emitted, and the decoded body in the trace payload missed the
;; schema-classification + `:rf.http/off-box-body` disposition that the
;; throw / malformed-return accept-failure branches already apply. This is the
;; structural parity with those branches (they route through
;; `finalise-failure!` → `emit-and-dispatch-failure!`).

(deftest accept-failure-emits-category-trace-with-schema-classified-decoded
  (testing "rf2-ltaihw — an :accept returning {:failure ...} on a 2xx decode
            emits the :rf.http/accept-failure failure-category trace, the
            decoded body's schema-sensitive slot is redacted on-box, and the
            off-box disposition is stamped :classify for the schema body"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json"
                                   "{\"token\":\"bearer-secret\",\"status\":\"rejected\"}")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        (rf/reg-event :api/login
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/login")}
                    ;; Schema decode marks :token sensitive — the per-slot mark
                    ;; must redact the decoded body on the accept-failure trace.
                    :decode     [:map
                                 [:token {:sensitive? true} :string]
                                 [:status :string]]
                    ;; Domain accept failure: a structurally-valid 200 the app
                    ;; classifies as a failure.
                    :accept     (fn [decoded]
                                  (if (= "rejected" (:status decoded))
                                    {:failure {:reason :domain-rejected}}
                                    {:ok decoded}))
                    :on-failure [:api/failed]}]]}))
        (rf/reg-event :api/failed (fn [_ _] {}))

        (rf/dispatch-sync [:api/login])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/accept-failure (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/accept-failure (:operation %)) @captured))]
          ;; The failure-category trace MUST be emitted (the bypassed path).
          (is (some? ev)
              "the :rf.http/accept-failure failure-category trace is emitted
               (was bypassed: finalise-success! routed through dispatch-failure!
               which only emits :rf.http/replied)")
          ;; The off-box disposition is stamped forward for the decoded slot.
          (is (= :classify (get-in ev [:tags :rf.http/off-box-body]))
              "schema decoded body stamped :classify for the off-box projector")
          ;; The decoded body's schema-sensitive slot is redacted ON-BOX, matching
          ;; the throw / malformed-return accept-failure branches.
          (is (= :rf/redacted (get-in ev [:tags :decoded :token]))
              "the :token slot the :decode schema marks sensitive is redacted on
               the accept-failure trace's :decoded payload")
          (is (= "rejected" (get-in ev [:tags :decoded :status]))
              "the non-sensitive sibling slot rides verbatim"))
        (finally
          (stop-server! srv))))))

(deftest accept-failure-unschematized-decoded-stamps-off-box-omit
  (testing "rf2-ltaihw — an :accept {:failure ...} whose decoded body is
            UNSCHEMATIZED (:auto / :json decode) stamps :rf.http/off-box-body
            :omit (fail-closed), matching the throw / malformed accept-failure
            branches; the on-box :decoded still rides raw for the local operator"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json"
                                   "{\"echoed-token\":\"bearer-abc123\"}")))
          port (:port srv)
          captured (atom [])]
      (try
        (trace/register-listener! :test/capture
                                  (fn [ev] (swap! captured conj ev)))
        (rf/reg-event :api/login
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" port "/login")}
                    ;; Unschematized decode → off-box must fail closed (:omit).
                    :decode     :json
                    :accept     (fn [_decoded] {:failure {:reason :domain-rejected}})
                    :on-failure [:api/failed]}]]}))
        (rf/reg-event :api/failed (fn [_ _] {}))

        (rf/dispatch-sync [:api/login])

        (let [_  (wait-for!
                   (fn [] (some #(= :rf.http/accept-failure (:operation %)) @captured))
                   3000)
              ev (first (filter #(= :rf.http/accept-failure (:operation %)) @captured))]
          (is (some? ev)
              "the :rf.http/accept-failure failure-category trace is emitted")
          (is (= :omit (get-in ev [:tags :rf.http/off-box-body]))
              "unschematized decoded body stamped :omit for the off-box projector
               (fail-closed) — the off-box projector omits :decoded"))
        (finally
          (stop-server! srv))))))
