(ns re-frame.http-managed-test
  "Spec 014 `:rf.http/managed` on the JVM: its reply addressing, and the
  `java.net.http.HttpClient` transport against an in-process
  com.sun.net.httpserver server."
  (:require [clojure.string]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.http.json :as rf.http.json]
            [re-frame.http.decode :as rf.http.decode]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- with-server
  "Run `(f base-url)` against an in-process server answering with `handler`."
  [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setHandler (.createContext server "/")
                 (reify HttpHandler (handle [_ exchange] (handler exchange))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
         (finally (.stop server 0)))))

(defn- write-response! [^HttpExchange exchange status content-type body]
  (let [bytes (.getBytes (str body) "UTF-8")]
    (-> exchange .getResponseHeaders (.set "Content-Type" content-type))
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- respond [status content-type body]
  (fn [exchange] (write-response! exchange status content-type body)))

(defn- poll! [pred label]
  (rf.test-support/poll-until pred {:timeout-ms 8000 :label label}))

(defn- await-reply!
  "Poll `:rf/default`'s app-db until `(pred db)`; return that db."
  [pred]
  (poll! #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
         "http-managed reply"))

(defn- fire!
  "Dispatch one event whose only effect is `[:rf.http/managed args]`. A reply
  target `[::replied k]` stores the reply appended to it under `[::replies k]`."
  [args]
  (rf/reg-event ::replied (fn [{:keys [db]} [_ k reply]] {:db (assoc-in db [::replies k] reply)}))
  (rf/reg-event ::fire (fn [_ _] {:fx [[:rf.http/managed args]]}))
  (rf/dispatch-sync [::fire]))

(defn- request!
  "Issue `args` through the live transport; return the reply once it lands."
  [args]
  (let [k (keyword (gensym "reply"))]
    (fire! (assoc args :reply-to [::replied k]))
    (get-in (await-reply! #(contains? (::replies %) k)) [::replies k])))

(defn- capturing-traces
  "Run `(f traces)` with every trace event collected into the atom `traces`."
  [f]
  (let [traces (atom [])
        id     (keyword (gensym "http-managed-traces"))]
    (rf.trace.tooling/register-listener! id #(swap! traces conj %))
    (try (f traces)
         (finally (rf.trace.tooling/unregister-listener! id)))))

(defn- ops [traces op] (filterv #(= op (:operation %)) @traces))

(defn- thrown-data [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ---- reply addressing -----------------------------------------------------

(deftest no-reply-target-fails-loud
  (testing "a request with none of :reply-to / :on-success / :on-failure throws
            :rf.error/http-no-reply-target at fx-call time; :reply-to or the
            split pair, even as explicit nils, addresses the reply"
    (let [issue (fn [args]
                  (thrown-data #(rf.http.managed/managed-handler
                                  {:frame :rf/default :event [:no-op]}
                                  (assoc args :request {:method :get :url "http://127.0.0.1:1/x"}))))]
      (is (= :rf.error/http-no-reply-target (:rf.error/id (issue {}))))
      (is (nil? (issue {:reply-to nil})) "an explicit nil :reply-to addresses the reply")
      (is (nil? (issue {:on-success nil :on-failure nil})) "so does an explicit nil split pair"))))

(deftest missing-and-malformed-reply-targets-refused
  (testing "the live fx refuses a missing, non-vector, mixed or unpaired reply
            addressing at fx-call time"
    (let [ctx     {:frame :rf/default :event [:no-op]}
          request {:request {:method :get :url "http://127.0.0.1:1/x"}}
          invoke  #(rf.http.managed/managed-handler ctx %)
          refused (fn [expected thunk]
                    (= expected (select-keys (thrown-data thunk) (keys expected))))]
      (is (refused {:rf.error/id :rf.error/http-no-reply-target} #(invoke request)))
      (doseq [[k partner] [[:reply-to nil] [:on-success :on-failure] [:on-failure :on-success]]]
        (is (refused {:rf.error/id :rf.error/http-bad-reply-target :key k}
                     #(invoke (cond-> (assoc request k {:not :a-vector})
                                partner (assoc partner nil))))
            (str "a map-valued " k)))
      (is (refused {:rf.error/id :rf.error/http-bad-reply-target
                    :reason      :mixed-addressing
                    :keys        [:reply-to :on-failure]}
                   #(invoke (assoc request :reply-to nil :on-failure nil))))
      (doseq [k [:on-success :on-failure]]
        (is (refused {:rf.error/id :rf.error/http-bad-reply-target
                      :reason      :unpaired-split
                      :keys        [k]}
                     #(invoke (assoc request k nil)))
            (str k " without its partner"))))))

;; ---- decode helpers -------------------------------------------------------

(deftest binary-read-kind-resolves-binary-decode-modes
  (testing "a transport picks its body reader before consuming the body: :auto
            or an omitted :decode over a non-text Content-Type resolves to a
            binary kind"
    (are [decode headers expected] (= expected (rf.http.decode/binary-read-kind decode headers))
      :auto {"content-type" "image/png"}                :blob
      nil   {"content-type" "application/octet-stream"} :blob)))

(deftest decode-response-body-returns-native-binary-for-binary-modes
  (testing "a binary decode mode returns the pre-read :body-binary verbatim,
            not the lossy body text"
    (let [native (Object.)]
      (doseq [mode [:blob :array-buffer :form-data]]
        (is (identical? native
                        (rf.http.decode/decode-response-body
                          {:body-text "lossy-utf8-text" :body-binary native :headers {} :decode mode}))
            (str mode))))))

;; ---- JVM transport --------------------------------------------------------

(deftest jvm-empty-200-json-body-replies-success-nil
  (testing "an empty 200 application/json body (a no-content success) replies
            :ok with a nil :value through the whole JVM cascade"
    (with-server (respond 200 "application/json" "")
      (fn [base]
        (is (= [:ok nil]
               ((juxt :status :value) (request! {:request {:url (str base "/empty")} :decode :json}))))))))

(def ^:private retry-on-5xx
  {:on #{:rf.http/http-5xx} :max-attempts 3 :backoff {:base-ms 5 :factor 1 :max-ms 10}})

(deftest jvm-non-retried-decode-failure-emits-no-retry-attempt
  (testing "a failure outside :retry :on, on attempt 1, was never retried, so
            it emits no :rf.http/retry-attempt"
    (capturing-traces
      (fn [traces]
        (with-server (respond 200 "application/json" "{\"ok\":true}")
          (fn [base]
            (let [reply (request! {:request {:url (str base "/x")}
                                   :decode  (fn [_text _headers] (throw (ex-info "boom" {})))
                                   :retry   retry-on-5xx})]
              (is (= [:error :rf.http/decode-failure []]
                     [(:status reply) (get-in reply [:error :kind]) (ops traces :rf.http/retry-attempt)])))))))))

(deftest jvm-retry-eligible-exhaustion-still-emits-retry-attempts
  (testing "a 5xx under :retry {:on #{:rf.http/http-5xx} :max-attempts 3}
            hits the server three times and replies the last failure; each
            attempt that schedules another emits a retry-attempt with
            :recovery :retried, and the terminal one (no :next-backoff-ms)
            :no-recovery"
    (let [hits (atom 0)]
      (capturing-traces
        (fn [traces]
          (with-server (fn [ex] (swap! hits inc) (write-response! ex 500 "application/json" "{\"err\":true}"))
            (fn [base]
              (let [reply (request! {:request {:url (str base "/5xx")} :decode :json :retry retry-on-5xx})]
                (is (= [:error :rf.http/http-5xx 3]
                       [(:status reply) (get-in reply [:error :kind]) @hits]))
                (is (= [[true :retried] [true :retried] [false :no-recovery]]
                       (mapv (juxt #(some? (get-in % [:tags :next-backoff-ms])) :recovery)
                             (ops traces :rf.http/retry-attempt))))))))))))

(defn- statuses-in-turn
  "A handler answering the nth hit with the nth of `statuses`, the last one
  repeating, and counting hits in the atom `hits`."
  [hits statuses]
  (fn [ex]
    (let [status (nth statuses (min (dec (swap! hits inc)) (dec (count statuses))))]
      (write-response! ex status "application/json" (if (< status 300) "{\"ok\":true}" "{\"err\":true}")))))

(deftest jvm-retry-on-names-statuses
  (testing "an integer in :retry :on retries a failure whose :http-status it
            names, alone or beside the category keywords: a 429 retries under
            #{429} and a 404 does not"
    (are [on statuses expected]
         (= expected
            (let [hits (atom 0)]
              (with-server (statuses-in-turn hits statuses)
                (fn [base]
                  (let [reply (request! {:request {:url (str base "/s")}
                                         :decode  :json
                                         :retry   (assoc retry-on-5xx :on on)})]
                    [(:status reply) (get-in reply [:error :http-status]) @hits])))))
      #{429}                   [429 200]     [:ok nil 2]
      #{429}                   [404 200]     [:error 404 1]
      #{:rf.http/http-5xx 429} [503 429 200] [:ok nil 3])))

(deftest jvm-transport-failure
  (testing "connection refused classifies as :rf.http/transport and emits one
            :error trace row (only an abort is :info)"
    (capturing-traces
      (fn [traces]
        (let [reply (request! {:request {:url "http://127.0.0.1:1/never"} :decode :json})]
          (is (= [:error :rf.http/transport [:error]]
                 [(:status reply) (get-in reply [:error :kind])
                  (mapv :op-type (ops traces :rf.http/transport))])))))))

(deftest jvm-abort-then-server-release-emits-exactly-one-reply-rf2-on7sj
  (testing "a user abort of a slow request replies :cancelled once with an
            :info :rf.http/aborted row, and the server's later response fires
            no second reply"
    (let [latch   (CountDownLatch. 1)
          replies (atom [])]
      (capturing-traces
        (fn [traces]
          (with-server (fn [ex]
                         (.await latch 10 TimeUnit/SECONDS)
                         (write-response! ex 200 "application/json" "{\"late\":true}"))
            (fn [base]
              (rf/reg-event :on7sj/replied (fn [_ [_ reply]] (swap! replies conj reply) {}))
              (rf/reg-event :on7sj/load
                (fn [_ _] {:fx [[:rf.http/managed {:reply-to   [:on7sj/replied]
                                                   :request    {:url (str base "/slow")}
                                                   :request-id :on7sj/req
                                                   :decode     :json}]]}))
              (rf/reg-event :on7sj/abort (fn [_ _] {:fx [[:rf.http/managed-abort :on7sj/req]]}))
              (rf/dispatch-sync [:on7sj/load])
              (poll! #(contains? (rf.http.managed/in-flight-snapshot) :on7sj/req) "in flight")
              (rf/dispatch-sync [:on7sj/abort])
              (poll! #(seq @replies) "abort reply")
              (.countDown latch)
              ;; Proving an absence: no signal to poll for, so give a late
              ;; completion callback time to fire a second reply.
              (Thread/sleep 800)
              (is (= [[:cancelled :rf.http/aborted]]
                     (mapv (juxt :status (comp :kind :error)) @replies)))
              (is (= [[:info :user]]
                     (mapv (juxt :op-type (comp :reason :tags)) (ops traces :rf.http/aborted)))))))))))

(deftest jvm-decode-failures-carry-their-discriminators
  (testing "a 2xx body that fails decode replies :rf.http/decode-failure; a
            schema rejection sets :schema-validation-failure?, and only the
            keyword-cap overflow carries :reason :too-many-keys and its :limit"
    (let [bodies {"/schema" "{\"id\":\"oops\"}"
                  "/cap"    "{\"a\":1,\"b\":2,\"c\":3}"
                  "/syntax" "{\"a\": "}]
      (with-server (fn [^HttpExchange ex]
                     (write-response! ex 200 "application/json" (bodies (.getPath (.getRequestURI ex)))))
        (fn [base]
          (doseq [[path args expected]
                  [["/schema" {:decode [:map [:id :int]]}
                    {:schema-validation-failure? true}]
                   ["/cap" {:decode [:map-of :keyword :int] :rf.http/max-decoded-keys 2}
                    {:schema-validation-failure? false :reason :too-many-keys :limit 2}]
                   ["/syntax" {:decode :json}
                    {:reason nil :limit nil}]]]
            (let [reply    (request! (assoc args :request {:url (str base path)}))
                  expected (assoc expected :kind :rf.http/decode-failure)]
              (is (= [:error expected]
                     [(:status reply) (zipmap (keys expected) (map (:error reply) (keys expected)))])
                  path))))))))

(deftest jvm-accept-failures-classify-as-accept-failure
  (testing "on a 2xx that decoded, an :accept that returns {:failure ..},
            throws, or returns a malformed nil replies :rf.http/accept-failure
            (never :rf.http/decode-failure) carrying the pre-accept :decoded,
            and the user's :failure map as :detail"
    (with-server (fn [^HttpExchange ex]
                   (write-response! ex 200 "application/json"
                                    (if (= "/quota" (.getPath (.getRequestURI ex)))
                                      "{\"ok\":false,\"reason\":\"quota\"}"
                                      "{\"ok\":true}")))
      (fn [base]
        (doseq [[path accept expected]
                [["/quota"
                  (fn [decoded] (if (:ok decoded)
                                  {:ok decoded}
                                  {:failure {:kind :domain-rejected :reason (:reason decoded)}}))
                  {:detail {:kind :domain-rejected :reason "quota"} :decoded {:ok false :reason "quota"}}]
                 ["/throw" (fn [_] (throw (ex-info "accept boom" {}))) {:decoded {:ok true}}]
                 ["/nil" (fn [_] nil) {:decoded {:ok true}}]]]
          (let [reply    (request! {:request {:url (str base path)} :decode :json :accept accept})
                expected (assoc expected :kind :rf.http/accept-failure)]
            (is (= [:error expected]
                   [(:status reply) (select-keys (:error reply) (keys expected))])
                path)))))))

(deftest jvm-request-encoding-reaches-the-wire
  (testing "observed at the server: :params become an escaped query string;
            :request-content-type :json encodes the body and sets
            Content-Type unless the caller already set one; a vector header
            value goes out as repeated header lines"
    (let [seen (atom {})]
      (with-server (fn [^HttpExchange ex]
                     (let [headers (.getRequestHeaders ex)]
                       (swap! seen assoc (.getPath (.getRequestURI ex))
                              {:query         (.getRawQuery (.getRequestURI ex))
                               :content-types (vec (.get headers "Content-Type"))
                               :body          (slurp (.getRequestBody ex))
                               :x-multi       (vec (.get headers "X-Multi"))}))
                     (write-response! ex 200 "application/json" "{\"ok\":true}"))
        (fn [base]
          (request! {:request {:url (str base "/params") :params {:q "a b" :page 2}} :decode :json})
          (request! {:request {:method :post :url (str base "/json") :body {:name "widget"}
                               :request-content-type :json}
                     :decode :json})
          (request! {:request {:method :post :url (str base "/clash") :body {:name "widget"}
                               :headers {"Content-Type" "application/vnd.custom+json"}
                               :request-content-type :json}
                     :decode :json})
          (request! {:request {:url (str base "/multi") :headers {"X-Multi" ["alpha" "beta" "gamma"]}}
                     :decode :json})
          (let [s @seen]
            (is (= "q=a%20b&page=2" (get-in s ["/params" :query])))
            (is (= [["application/json"] {:name "widget"}]
                   [(get-in s ["/json" :content-types]) (rf.http.json/json-parse (get-in s ["/json" :body]))]))
            (is (= ["application/vnd.custom+json"] (get-in s ["/clash" :content-types])))
            (is (= ["alpha" "beta" "gamma"] (get-in s ["/multi" :x-multi])))))))))

(deftest jvm-keyword-header-names-reach-the-wire
  (testing "a keyword header name goes out as its name, so a keyword
            :authorization reaches the server and the default Content-Type is
            still added, while a keyword :content-type replaces that default"
    (let [seen (atom {})]
      (with-server (fn [^HttpExchange ex]
                     (let [headers (.getRequestHeaders ex)]
                       (swap! seen assoc (.getPath (.getRequestURI ex))
                              {:authorization (vec (.get headers "Authorization"))
                               :content-types (vec (.get headers "Content-Type"))}))
                     (write-response! ex 200 "application/json" "{\"ok\":true}"))
        (fn [base]
          (request! {:request {:method :post :url (str base "/auth") :body {:name "widget"}
                               :headers {:authorization "Bearer T"}}
                     :decode :json})
          (request! {:request {:method :post :url (str base "/csv") :body "a,b"
                               :headers {:content-type "text/csv"}}
                     :decode :json})
          (is (= {:authorization ["Bearer T"] :content-types ["application/json"]}
                 (get @seen "/auth")))
          (is (= ["text/csv"] (get-in @seen ["/csv" :content-types]))))))))

;; ---- issuance counters ----------------------------------------------------

(deftest issuance-counter-runs-per-frame
  (testing "a frame's issuance numbers rise by one per request, and a sibling
            frame's requests neither take nor perturb them"
    (is (= [1 1 2] [(rf.http.registry/next-issuance! :frame/a)
                    (rf.http.registry/next-issuance! :frame/b)
                    (rf.http.registry/next-issuance! :frame/a)]))))
