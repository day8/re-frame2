(ns re-frame.http-privacy-integration-test
  "Spec 014 §Privacy end-to-end: real `:rf.http/managed` requests against an
  in-process HTTP server, asserting the emitted `:rf.http/*` trace rows are
  redacted and stamped per the per-call `:sensitive?` flag, the header and
  query-param denylists, the `:carriers` extensions, and `:decode`-schema marks,
  while the reply DELIVERED to the app stays raw."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [malli.registry :as mr]
            [re-frame.core :as rf]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]
            [re-frame.fx :as rf.fx]
            [re-frame.http.managed :as rf.http.managed]
            ;; load-bearing: binds the shared schema walker hooks the
            ;; `:decode`-schema classification late-binds; without it every
            ;; schema-classified assertion below reads its secret back verbatim.
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; The registrar snapshot/restore also resets a `:carriers` re-registration and
;; the trace listeners between tests.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- harness ----------------------------------------------------------------

(defn- respond
  "A server handler answering `status` with `body`, after setting `headers`."
  ([status content-type body] (respond status content-type body nil))
  ([status content-type body headers]
   (fn [^HttpExchange ex]
     (doseq [[k v] headers] (-> ex .getResponseHeaders (.set k v)))
     (let [bytes (.getBytes (str body) "UTF-8")]
       (-> ex .getResponseHeaders (.set "Content-Type" content-type))
       (.sendResponseHeaders ex status (long (count bytes)))
       (with-open [os (.getResponseBody ex)] (.write os bytes))))))

(defn- wait-for! [pred]
  (rf.test-support/poll-until pred {:timeout-ms 3000 :label "http-privacy wait-for"}))

(defn- managed-trace!
  "Serve with `handler`, dispatch one `:rf.http/managed` request whose args are
  `(args-fn base-url)`, and return `[first-row-with-operation base-url]`.
  `[:test/ok]` stores the reply it receives at `:reply` in app-db."
  [handler args-fn operation]
  (let [server   (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                   (-> (.createContext "/")
                       (.setHandler (reify HttpHandler (handle [_ ex] (handler ex)))))
                   (.setExecutor nil)
                   (.start))
        base     (str "http://127.0.0.1:" (.getPort (.getAddress server)))
        captured (atom [])]
    (try
      (rf.trace.tooling/register-listener! :test/capture #(swap! captured conj %))
      (rf/reg-event :test/ok (fn [{:keys [db]} [_ reply]] {:db (assoc db :reply reply)}))
      (rf/reg-event :test/go (fn [_ _] {:fx [[:rf.http/managed (args-fn base)]]}))
      (rf/dispatch-sync [:test/go])
      [(wait-for! (fn [] (some #(when (= operation (:operation %)) %) @captured))) base]
      (finally (.stop server 0)))))

(defn- delivered-reply []
  (:reply (wait-for! #(let [db (rf/app-db-value :rf/default)] (when (:reply db) db)))))

(defn- find-header
  "Case-insensitive header lookup: the JDK normalises header-name casing."
  [headers-map header-name]
  (some (fn [[k v]] (when (= (str/lower-case header-name) (str/lower-case (str k))) v))
        headers-map))

(defn- reg-carriers! [carriers]
  (rf.fx/reg-fx :rf.http/managed {:carriers carriers} rf.http.managed/managed-handler))

(defn- off-box
  "The trace row as epoch's off-box trace-events projector leaves it: the body
  slot of an `:omit`-stamped row is replaced, a `:classify` row passes as is."
  [ev]
  (first (#'rf.epoch.tool-pair/omit-off-box-http-bodies [ev] {})))

;; ---- failure rows: headers and URL ------------------------------------------

(deftest sensitive-headers-redacted-in-failure-tags
  (testing "a denylisted response header is redacted on the failure row of a
            request that is not :sensitive?"
    (let [[ev] (managed-trace! (respond 500 "text/plain" "boom" {"Set-Cookie" "sid=secret"})
                               (fn [base] {:request {:url (str base "/x")} :on-failure nil})
                               :rf.http/http-5xx)]
      (is (= :rf/redacted (find-header (get-in ev [:tags :headers]) "Set-Cookie"))))))

(deftest sensitive-query-param-redacted-in-failure-url
  (testing "a denylisted param's value is redacted and the name alone stamps
            :sensitive?"
    (let [[ev base] (managed-trace! (respond 500 "text/plain" "boom")
                                    (fn [base] {:request    {:url (str base "/x?api_key=SECRET&page=2")}
                                                :on-failure nil})
                                    :rf.http/http-5xx)]
      (is (= (str base "/x?api_key=:rf/redacted&page=2") (get-in ev [:tags :url])))
      (is (true? (:sensitive? ev))))))

(deftest sensitive-request-redacts-all-url-query-params
  (testing "a per-call :sensitive? request scrubs every param value and its
            body, and stamps the row"
    (let [[ev base] (managed-trace! (respond 500 "text/plain" "boom")
                                    (fn [base] {:request    {:url (str base "/x?user_id=42&page=2")}
                                                :sensitive? true
                                                :on-failure nil})
                                    :rf.http/http-5xx)]
      (is (true? (:sensitive? ev)))
      (is (= :rf/redacted (get-in ev [:tags :body])))
      (is (= (str base "/x?user_id=:rf/redacted&page=:rf/redacted") (get-in ev [:tags :url]))))))

(deftest managed-carrier-query-param-redacts-failure-url
  (reg-carriers! {:query-params ["shop_token"]})
  (let [[ev base] (managed-trace! (respond 500 "text/plain" "boom")
                                  (fn [base] {:request    {:url (str base "/x?shop_token=abc&page=2")}
                                              :on-failure nil})
                                  :rf.http/http-5xx)]
    (is (= (str base "/x?shop_token=:rf/redacted&page=2") (get-in ev [:tags :url])))))

;; ---- response bodies: `:decode`-schema marks and the off-box stamp ----------

(deftest response-body-decode-schema-sensitive-slot-redacted-in-replied-trace
  (testing "a schema-marked slot redacts with no per-call :sensitive?, and the
            schema body is stamped :classify for the off-box projector"
    (let [[ev] (managed-trace! (respond 200 "application/json" "{\"token\":\"bearer-secret\",\"user-id\":42}")
                               (fn [base] {:request    {:url (str base "/login")}
                                           :decode     [:map [:token {:sensitive? true} :string] [:user-id :int]]
                                           :on-success [:test/ok]})
                               :rf.http/replied)]
      (is (= {:token :rf/redacted :user-id 42} (get-in ev [:tags :value])))
      (is (= :classify (get-in ev [:tags :rf.http/off-box-body]))))))

(def ^:private user-schema
  [:map [:id :int] [:token {:sensitive? true} :string]])

(defn- with-default-registry-schema
  "Run `f` with `k` naming `schema` in Malli's default registry, so a decoder
  can reference it by keyword; the registry is restored afterwards."
  [k schema f]
  (let [saved @@#'mr/registry*]
    (try
      (mr/set-default-registry! (mr/composite-registry saved {k schema}))
      (f)
      (finally
        (mr/set-default-registry! saved)))))

(defn- assert-omitted-off-box
  "The row for a `decode` whose marks the walker cannot see: stamped `:omit`,
  no secret off-box, and the app still receives the raw body."
  [decode]
  (let [[ev] (managed-trace! (respond 200 "application/json" "{\"id\":1,\"token\":\"SECRET\"}")
                             (fn [base] {:request  {:url (str base "/user")}
                                         :decode   decode
                                         :reply-to [:test/ok]})
                             :rf.http/replied)]
    (is (= :omit (get-in ev [:tags :rf.http/off-box-body])))
    (is (not (str/includes? (pr-str (off-box ev)) "SECRET")))
    (is (= {:id 1 :token "SECRET"} (:value (delivered-reply))))))

(deftest ref-decoder-is-omitted-off-box
  (with-default-registry-schema :app/user user-schema
    #(assert-omitted-off-box [:ref :app/user])))

(deftest local-registry-decoder-is-omitted-off-box
  (assert-omitted-off-box [:schema {:registry {:app/u2 user-schema}} :app/u2]))

(deftest vector-of-maps-decoder-redacts-every-element
  (testing "a mark inside a collection's element schema redacts that slot in
            every element, on the dev trace and off-box, while the unmarked
            :id and the unmarked sibling [:meta :token] stay visible"
    (let [[ev] (managed-trace! (respond 200 "application/json"
                                        (str "{\"items\":[{\"id\":1,\"token\":\"SECRET-1\"},"
                                             "{\"id\":2,\"token\":\"SECRET-2\"}],"
                                             "\"meta\":{\"token\":\"public\"}}"))
                               (fn [base] {:request  {:url (str base "/users")}
                                           :decode   [:map
                                                      [:items [:vector user-schema]]
                                                      [:meta [:map [:token :string]]]]
                                           :reply-to [:test/ok]})
                               :rf.http/replied)
          redacted {:items [{:id 1 :token :rf/redacted} {:id 2 :token :rf/redacted}]
                    :meta  {:token "public"}}]
      (is (= redacted (get-in ev [:tags :value])))
      (is (= :classify (get-in ev [:tags :rf.http/off-box-body])))
      (is (= redacted (get-in (off-box ev) [:tags :value])))
      (is (= {:items [{:id 1 :token "SECRET-1"} {:id 2 :token "SECRET-2"}]
              :meta  {:token "public"}}
             (:value (delivered-reply)))))))

(deftest response-body-whole-body-sensitive-decode-schema-redacts-all
  (let [[ev] (managed-trace! (respond 200 "application/json" "\"opaque-token-value\"")
                             (fn [base] {:request    {:url (str base "/refresh")}
                                         :decode     [:string {:sensitive? true}]
                                         :on-success [:test/ok]})
                             :rf.http/replied)]
    (is (= :rf/redacted (get-in ev [:tags :value])))))

(deftest replied-trace-stamps-off-box-omit-for-unschematized-body
  (testing "an unschematized body is stamped :omit for the off-box projector
            while the on-box value rides raw for the local operator"
    (let [[ev] (managed-trace! (respond 200 "application/json" "{\"opaque\":\"raw-token\"}")
                               (fn [base] {:request {:url (str base "/opaque")} :on-success [:test/ok]})
                               :rf.http/replied)]
      (is (= :omit (get-in ev [:tags :rf.http/off-box-body])))
      (is (= {:opaque "raw-token"} (get-in ev [:tags :value]))))))

;; A raw error body is unschematized by construction (status classification
;; runs before decode), so it is stamped :omit whatever the per-call flag.
(deftest http-5xx-stamps-off-box-omit-on-raw-body-non-sensitive
  (let [[ev] (managed-trace! (respond 500 "text/plain" "error: token=bearer-abc123 rejected")
                             (fn [base] {:request {:url (str base "/data")} :on-failure nil})
                             :rf.http/http-5xx)]
    (is (= :omit (get-in ev [:tags :rf.http/off-box-body])))
    (is (= "error: token=bearer-abc123 rejected" (get-in ev [:tags :body]))
        "on-box the raw body still rides")
    (is (nil? (:sensitive? ev)) "no :sensitive? stamp without a denylist hit or the flag")))

(deftest decode-failure-stamps-off-box-omit-on-raw-body-text
  (let [[ev] (managed-trace! (respond 200 "application/json" "not-json: leaked-token=xyz")
                             (fn [base] {:request {:url (str base "/data")} :decode :json :on-failure nil})
                             :rf.http/decode-failure)]
    (is (= :omit (get-in ev [:tags :rf.http/off-box-body])))))

;; A 2xx whose `:accept` returns `{:failure …}` is an `:rf.http/accept-failure`:
;; its category row carries the schema-classified pre-`:accept` body.
(deftest accept-failure-emits-category-trace-with-schema-classified-decoded
  (let [[ev] (managed-trace! (respond 200 "application/json" "{\"token\":\"bearer-secret\",\"status\":\"rejected\"}")
                             (fn [base] {:request    {:url (str base "/login")}
                                         :decode     [:map [:token {:sensitive? true} :string] [:status :string]]
                                         :accept     (fn [_] {:failure {:reason :domain-rejected}})
                                         :on-failure [:test/ok]})
                             :rf.http/accept-failure)]
    (is (= :classify (get-in ev [:tags :rf.http/off-box-body])))
    (is (= {:token :rf/redacted :status "rejected"} (get-in ev [:tags :decoded])))))

;; ---- the `:rf.http/replied` completion row ----------------------------------

(deftest replied-trace-redacts-denylisted-headers-in-the-failure-error-slot
  (testing "a failed request's replied row seats the failure map at :error, so
            its denylisted response headers are redacted THERE, on a request
            that declares no :sensitive?; the delivered reply stays raw"
    (let [[ev] (managed-trace! (respond 403 "text/plain" "forbidden: echoes-token-SECRET"
                                        {"Set-Cookie"       "session=SECRET-COOKIE; Path=/"
                                         "WWW-Authenticate" "Bearer realm=\"SECRET-REALM\""})
                               (fn [base] {:request {:url (str base "/guarded")} :reply-to [:test/ok]})
                               :rf.http/replied)]
      (is (= "session=SECRET-COOKIE; Path=/"
             (find-header (get-in (delivered-reply) [:error :headers]) "Set-Cookie")))
      (is (= :rf/redacted (get-in ev [:tags :error :headers "set-cookie"])))
      (is (not (re-find #"SECRET-COOKIE|SECRET-REALM" (pr-str (:tags ev))))
          "no raw header value survives anywhere in the tags")
      (is (= :omit (get-in ev [:tags :rf.http/off-box-body]))
          "the raw error body is stamped :omit on the replied row too"))))

(deftest replied-trace-redacts-builtin-sensitive-response-meta-headers
  (testing "a success reply's denylisted :meta header is redacted on the trace
            while the delivered reply keeps it"
    (let [[ev] (managed-trace! (respond 200 "application/json" "{\"ok\":true}"
                                        {"Set-Cookie" "session=SECRET-COOKIE; Path=/"})
                               (fn [base] {:request {:url (str base "/x")} :decode :json :reply-to [:test/ok]})
                               :rf.http/replied)]
      (is (= "session=SECRET-COOKIE; Path=/" (get-in (delivered-reply) [:meta :headers "set-cookie"])))
      (is (= :rf/redacted (get-in ev [:tags :meta :headers "set-cookie"]))))))

(deftest replied-trace-redacts-app-declared-carrier-in-response-meta
  (reg-carriers! {:headers ["X-Honeycomb-Team"]})
  (let [[ev] (managed-trace! (respond 200 "application/json" "{\"ok\":true}" {"X-Honeycomb-Team" "hc-token"})
                             (fn [base] {:request {:url (str base "/x")} :decode :json :reply-to [:test/ok]})
                             :rf.http/replied)]
    (is (= :rf/redacted (get-in ev [:tags :meta :headers "x-honeycomb-team"])))))

(deftest sensitive-request-force-redacts-response-meta-wholesale
  (testing "per-call :sensitive? redacts the whole :meta wire slot, as it does :value"
    (let [[ev] (managed-trace! (respond 200 "application/json" "{\"ok\":true}")
                               (fn [base] {:request    {:url (str base "/x")}
                                           :sensitive? true
                                           :decode     :json
                                           :reply-to   [:test/ok]})
                               :rf.http/replied)]
      (is (= :rf/redacted (get-in ev [:tags :meta])))
      (is (= :rf/redacted (get-in ev [:tags :value]))))))
