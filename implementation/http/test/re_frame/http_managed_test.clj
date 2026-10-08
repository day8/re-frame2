(ns re-frame.http-managed-test
  "Spec 014 `:rf.http/managed` on the JVM: the canned-stub and route-map test
  doubles (which must refuse and reply exactly as the live fx would), and the
  `java.net.http.HttpClient` transport against an in-process
  com.sun.net.httpserver server."
  (:require [clojure.string]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.http.json :as rf.http.json]
            [re-frame.registrar :as rf.registrar]
            [re-frame.http.decode :as rf.http.decode]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.http.test-support :as rf.http.test-support]
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

(def ^:private canned-success {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
(def ^:private canned-failure {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})

(defn- fire!
  "Dispatch one event whose only effect is `[:rf.http/managed args]`. A reply
  target `[::replied k]` stores the reply appended to it under `[::replies k]`."
  ([args] (fire! args {}))
  ([args opts]
   (rf/reg-event ::replied (fn [{:keys [db]} [_ k reply]] {:db (assoc-in db [::replies k] reply)}))
   (rf/reg-event ::fire (fn [_ _] {:fx [[:rf.http/managed args]]}))
   (rf/dispatch-sync [::fire] opts)))

(defn- replies [] (::replies (rf/app-db-value :rf/default)))

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

(defn- with-installed-stubs
  "Install `routes` with `install-managed-request-stubs!` and swap the reply
  router for a recorder, then run `(f stub-fx recorded)`."
  [routes f]
  (let [recorded (atom [])
        original (rf.late-bind/get-fn :router/dispatch!)]
    (rf.late-bind/set-fn! :router/dispatch! (fn [ev opts] (swap! recorded conj [ev opts])))
    (try
      (rf.http.test-support/install-managed-request-stubs! routes)
      (f (rf.registrar/handler :fx :rf.http/managed-test-stub) recorded)
      (finally
        (rf.http.test-support/uninstall-managed-request-stubs!)
        (rf.late-bind/set-fn! :router/dispatch! original)))))

(defn- thrown-data [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ---- reply addressing -----------------------------------------------------

(deftest canned-success-reply-to-addressing
  (testing "the canned-success stub appends the canonical envelope, :value
            defaulting to {:stubbed true}, to a :reply-to target and to an
            :on-success target alike"
    (doseq [via [:reply-to :on-success]]
      (fire! {via [::replied via] :request {:method :get :url "/articles/hello"} :decode :json}
             canned-success))
    (is (= {:reply-to   {:status :ok :value {:stubbed true}}
            :on-success {:status :ok :value {:stubbed true}}}
           (replies)))))

(deftest no-reply-target-fails-loud
  (testing "a request with none of :reply-to / :on-success / :on-failure throws
            :rf.error/http-no-reply-target at fx-call time; any one of them,
            even an explicit nil, addresses the reply"
    (let [issue (fn [args]
                  (thrown-data #(rf.http.managed/managed-handler
                                  {:frame :rf/default :event [:no-op]}
                                  (assoc args :request {:method :get :url "http://127.0.0.1:1/x"}))))]
      (is (= :rf.error/http-no-reply-target (:rf.error/id (issue {}))))
      (doseq [k [:reply-to :on-success :on-failure]]
        (is (nil? (issue {k nil})) (str "an explicit nil " k " addresses the reply"))))))

(def ^:private stub-handler @#'rf.http.test-support/stub-handler)

(deftest missing-and-malformed-reply-targets-refused-on-every-interpreting-path
  (testing "the live fx, both canned stubs and the route-map stub refuse a
            missing, non-vector or mixed reply addressing with the live fx's
            own error, so a test double never green-lights a call site
            production refuses"
    (let [ctx     {:frame :rf/default :event [:no-op]}
          request {:request {:method :get :url "http://127.0.0.1:1/x"}}
          refused (fn [expected thunk]
                    (= expected (select-keys (thrown-data thunk) (keys expected))))]
      (doseq [[label invoke]
              [["the live fx" #(rf.http.managed/managed-handler ctx %)]
               [":rf.http/managed-canned-success" #(rf.http.test-support/canned-success-handler ctx %)]
               [":rf.http/managed-canned-failure" #(rf.http.test-support/canned-failure-handler ctx %)]
               ["the route-map stub" #(stub-handler {[:get "http://127.0.0.1:1/x"] {:reply {:ok true}}} ctx %)]]]
        (testing label
          (is (refused {:rf.error/id :rf.error/http-no-reply-target} #(invoke request)))
          (doseq [k [:reply-to :on-success :on-failure]]
            (is (refused {:rf.error/id :rf.error/http-bad-reply-target}
                         #(invoke (assoc request k :items/loaded)))
                (str "a bare-keyword " k)))
          (is (refused {:rf.error/id :rf.error/http-bad-reply-target
                        :reason      :mixed-addressing
                        :keys        [:reply-to :on-failure]}
                       #(invoke (assoc request :reply-to [:a] :on-failure nil)))))))))

;; ---- canned stubs ---------------------------------------------------------

(deftest after-ms-positive-defers-via-dispatch-later
  (testing ":after-ms 0 replies inside the dispatch-sync drain; a positive
            :after-ms defers the reply by one :dispatch-later tick, visible in
            the tape as the framework deliverer rather than a raw timer"
    (fire! {:reply-to [::replied 0] :request {:method :get :url "/later"} :value {:n 0} :after-ms 0}
           canned-success)
    (is (= {:n 0} (get-in (replies) [0 :value])) ":after-ms 0 replies synchronously")
    (capturing-traces
      (fn [traces]
        (fire! {:reply-to [::replied 30] :request {:method :get :url "/later"} :value {:n 30} :after-ms 30}
               canned-success)
        (is (nil? (get (replies) 30)) "not delivered inside the dispatch-sync drain")
        (is (= {:n 30} (get-in (await-reply! #(get-in % [::replies 30])) [::replies 30 :value])))
        (is (some #(and (= :rf.event/dispatched (:operation %))
                        (= :fx-dispatch-later (:source %))
                        (= :rf.http/deliver-canned-reply (first (get-in % [:tags :rf.event/v]))))
                  @traces)
            "the deferred delivery rode :dispatch-later")))))

(deftest after-ms-positive-on-failure-defers
  (testing "a positive :after-ms defers the canned-failure reply too, and the
            deferred re-fire is still the failure fx"
    (fire! {:on-failure [::replied :failed] :request {:method :get :url "/later/fail"} :after-ms 30}
           canned-failure)
    (is (nil? (replies)) "not delivered inside the dispatch-sync drain")
    (is (= :rf.http/transport
           (get-in (await-reply! #(get-in % [::replies :failed])) [::replies :failed :error :kind])))))

(deftest canned-stubs-run-the-after-interceptor-chain
  (testing "both canned fxs walk :before then :after, as the live transport
            does, and the :after transform reaches the reply"
    (let [order (atom [])]
      (rf/reg-http-interceptor :canned/touch
        {:before (fn [ctx] (swap! order conj :before) ctx)
         :after  (fn [_ctx resp] (swap! order conj :after) (assoc resp :touched-by :after))})
      (fire! {:reply-to [::replied :ok] :request {:method :get :url "/touch"} :value {:ok true}}
             canned-success)
      (fire! {:reply-to [::replied :error] :request {:method :get :url "/touch"}}
             canned-failure)
      (is (= [:before :after :before :after] @order))
      (is (= {:ok    {:status :ok :value {:ok true} :touched-by :after}
              :error {:status :error :error {:kind :rf.http/transport} :touched-by :after}}
             (replies))))))

;; ---- route-map stubs ------------------------------------------------------

(deftest with-request-stubs-optional-response-meta
  (testing "a route entry's optional :meta rides the canned reply's :meta
            verbatim; an entry without one gets no fabricated :meta"
    (rf.http.test-support/with-request-stubs
      {[:get "/with-meta"] {:reply {:ok   {:v 1}
                                    :meta {:status 200 :headers {"x-ratelimit-remaining" "37"}}}}
       [:get "/no-meta"]   {:reply {:ok {:v 2}}}}
      (fn []
        (doseq [url ["/with-meta" "/no-meta"]]
          (fire! {:reply-to [::replied url] :request {:method :get :url url} :decode :json}))))
    (is (= {"/with-meta" {:status :ok :value {:v 1}
                          :meta   {:status 200 :headers {"x-ratelimit-remaining" "37"}}}
            "/no-meta"   {:status :ok :value {:v 2}}}
           (replies)))))

(deftest stub-matches-post-before-url-rewrite-rf2-azrcs
  (testing "the route-map stub keys its match off the url the :before chain
            leaves, not the draft: a route keyed to the rewritten url matches,
            and one keyed to the draft misses with a no-stub-matched failure
            naming the final url"
    (rf/reg-http-interceptor :azrcs/base-url
      {:before (fn [ctx] (update-in ctx [:request :url] #(clojure.string/replace % #"^/" "/v2/")))})
    (doseq [[k routes] [[:final {[:get "/v2/articles"] {:reply {:ok [:rewritten :ok]}}}]
                        [:draft {[:get "/articles"] {:reply {:ok [:should :not :match]}}}]]]
      (rf.http.test-support/with-request-stubs routes
        #(fire! {:reply-to [::replied k] :request {:method :get :url "/articles"} :decode :json})))
    (is (= {:final {:status :ok :value [:rewritten :ok]}
            :draft {:status :error
                    :error  {:kind :rf.http/transport :message "no stub matched"
                             :method :get :url "/v2/articles"}}}
           (replies)))))

(deftest stub-url-erasing-before-throws-bad-request-rf2-azrcs
  (testing "a :before that blanks the url makes the route-map stub throw the
            production :rf.error/http-bad-request and dispatch no reply"
    (rf/reg-http-interceptor :azrcs/url-eraser
      {:before (fn [ctx] (assoc-in ctx [:request :url] nil))})
    (with-installed-stubs {[:get "/x"] {:reply {:ok {:stubbed true}}}}
      (fn [stub-fx recorded]
        (is (= [:rf.error/http-bad-request []]
               [(:rf.error/id (thrown-data #(stub-fx {:frame :rf/default :event [:azrcs/erase]}
                                                     {:request {:method :get :url "/x"} :reply-to [:azrcs/loaded]})))
                @recorded]))))))

(deftest stub-request-chain-failure-honours-top-level-sensitive-rf2-xmp74u
  (testing "a throwing :before on the route-map stub path raises
            :rf.error/http-interceptor-failed and dispatches no reply; its
            trace redacts every query value and stamps :sensitive? exactly
            when the request opted in through TOP-LEVEL :sensitive?, as
            production does (customer_email is not on the denylist, so only
            request sensitivity can scrub it)"
    (rf/reg-http-interceptor :xmp74u/boom
      {:before (fn [_ctx] (throw (ex-info "kaboom" {})))})
    (let [url "https://api.example.invalid/v1?customer_email=alice%40example.com&page=2"]
      (with-installed-stubs {[:get "https://api.example.invalid/v1"] {:reply {:ok {:stubbed true}}}}
        (fn [stub-fx recorded]
          (doseq [[extra trace-url stamped?]
                  [[{:sensitive? true}
                    "https://api.example.invalid/v1?customer_email=:rf/redacted&page=:rf/redacted" true]
                   [{} url false]]]
            (capturing-traces
              (fn [traces]
                (let [id (:rf.error/id (thrown-data #(stub-fx {:frame :rf/default :event [:xmp74u/load]}
                                                              (merge {:request  {:method :get :url url}
                                                                      :reply-to [:xmp74u/loaded]}
                                                                     extra))))
                      w  (first (ops traces :rf.error/http-interceptor-failed))]
                  (is (= [:rf.error/http-interceptor-failed trace-url stamped?]
                         [id (get-in w [:tags :url]) (true? (:sensitive? w))])
                      (pr-str extra))))))
          (is (= [] @recorded)))))))

(deftest scoped-stubs-compose-under-nesting-rf2-vn8qjv
  (testing "an inner with-request-stubs scope routes to its own map, and once
            it exits the outer scope's map is live again"
    (rf.http.test-support/with-request-stubs {[:get "/a"] {:reply {:ok {:from :outer-a}}}}
      (fn []
        (rf.http.test-support/with-request-stubs {[:get "/b"] {:reply {:ok {:from :inner-b}}}}
          #(fire! {:reply-to [::replied :b] :request {:method :get :url "/b"}}))
        (fire! {:reply-to [::replied :a] :request {:method :get :url "/a"}})))
    (is (= {:b {:status :ok :value {:from :inner-b}}
            :a {:status :ok :value {:from :outer-a}}}
           (replies)))))

(deftest stubs-intercept-inside-pre-created-sealed-frame-rf2-bxc8kf
  (testing "in a frame created before with-request-stubs (its image generation
            already sealed), a plain dispatch-sync routes through the stub and
            never reaches the real :rf.http/managed"
    (let [real-fx-invoked? (atom false)]
      ;; Registered before make-frame, so the sealed generation sees the
      ;; sentinel; reaching it means the override did not resolve.
      (rf.fx/reg-fx :rf.http/managed (fn [_ _] (reset! real-fx-invoked? true) nil))
      (rf/reg-event :bxc8kf/loaded (fn [{:keys [db]} [_ reply]] {:db (assoc db :result reply)}))
      (rf/reg-event :bxc8kf/load
        (fn [_ _] {:fx [[:rf.http/managed {:reply-to [:bxc8kf/loaded] :request {:method :get :url "/x"}}]]}))
      (rf/with-new-frame [f (rf/make-frame {})]
        (rf.http.test-support/with-request-stubs {[:get "/x"] {:reply {:ok {:stubbed true}}}}
          #(rf/dispatch-sync [:bxc8kf/load]))
        (is (= [{:status :ok :value {:stubbed true}} false]
               [(:result (rf/app-db-value f)) @real-fx-invoked?]))))))

(deftest lower-level-install-uninstall-stack-discipline-rf2-vn8qjv
  (testing "a nested install/uninstall pair on the stable
            :rf.http/managed-test-stub id restores the outer install; a
            balanced top-level pair leaks no fx, and an extra uninstall is a
            no-op"
    (let [handler    #(rf.registrar/handler :fx :rf.http/managed-test-stub)
          install!   rf.http.test-support/install-managed-request-stubs!
          uninstall! rf.http.test-support/uninstall-managed-request-stubs!
          outer      (do (install! {[:get "/outer"] {:reply {:ok {:from :outer}}}}) (handler))
          inner      (do (install! {[:get "/inner"] {:reply {:ok {:from :inner}}}}) (handler))
          restored   (do (uninstall!) (handler))
          cleared    (do (uninstall!) (handler))
          extra      (do (uninstall!) (handler))]
      (is (some? outer))
      (is (not (identical? outer inner)) "the inner install replaces the handler")
      (is (identical? outer restored) "the inner uninstall restores the outer install")
      (is (= [nil nil] [cleared extra])))))

;; ---- decode helpers -------------------------------------------------------

(deftest content-type-of-case-insensitive
  (testing "the Content-Type header resolves whatever its name's casing or key
            type, so :auto still sniffs JSON from a hand-built headers map"
    (is (= {:ok true}
           (rf.http.decode/decode-response-body
             {:body-text "{\"ok\":true}" :headers {"Content-type" "application/json"} :decode :auto})))
    (is (= "application/json" (rf.http.decode/content-type-of {:Content-Type "application/json"})))))

(deftest binary-read-kind-resolves-binary-decode-modes
  (testing "a transport picks its body reader before consuming the body: an
            explicit binary mode, or :auto / omitted :decode over a non-text
            Content-Type, resolves to a binary kind; a text mode to nil"
    (are [decode headers expected] (= expected (rf.http.decode/binary-read-kind decode headers))
      :blob {}                                          :blob
      :json {}                                          nil
      :auto {"content-type" "image/png"}                :blob
      nil   {"content-type" "application/octet-stream"} :blob
      :auto {"content-type" "application/json"}         nil)))

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

(deftest jvm-html-404-with-json-decode-routes-to-http-4xx
  (testing "status classification precedes decode: an HTML 404 under
            :decode :json is :rf.http/http-4xx carrying the raw body, never a
            decode failure"
    (let [html "<!doctype html><html><body><h1>Not Found</h1></body></html>"]
      (with-server (respond 404 "text/html" html)
        (fn [base]
          (let [reply (request! {:request {:url (str base "/missing")} :decode :json})]
            (is (= [:error {:kind :rf.http/http-4xx :status 404 :body html}]
                   [(:status reply) (select-keys (:error reply) [:kind :status :body])]))))))))

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

(deftest jvm-retry-recover
  (testing ":retry recovers when an intermediate attempt succeeds"
    (let [hits (atom 0)]
      (with-server (fn [ex]
                     (if (= 1 (swap! hits inc))
                       (write-response! ex 500 "application/json" "{\"err\":true}")
                       (write-response! ex 200 "application/json" "{\"ok\":true}")))
        (fn [base]
          (let [reply (request! {:request {:url (str base "/recover")} :decode :json :retry retry-on-5xx})]
            (is (= [:ok {:ok true} 2] [(:status reply) (:value reply) @hits]))))))))

(deftest jvm-transport-failure
  (testing "connection refused classifies as :rf.http/transport and emits one
            :error trace row (only an abort is :info)"
    (capturing-traces
      (fn [traces]
        (let [reply (request! {:request {:url "http://127.0.0.1:1/never"} :decode :json})]
          (is (= [:error :rf.http/transport [:error]]
                 [(:status reply) (get-in reply [:error :kind])
                  (mapv :op-type (ops traces :rf.http/transport))])))))))

(deftest jvm-managed-abort-unknown-request-id-is-silent-noop
  (testing "aborting a request-id the registry never saw, once or twice, is a
            silent no-op"
    (capturing-traces
      (fn [traces]
        (rf/reg-event :abort/unknown (fn [_ _] {:fx [[:rf.http/managed-abort :never-issued]]}))
        (rf/dispatch-sync [:abort/unknown])
        (rf/dispatch-sync [:abort/unknown])
        (is (= [] (mapv :operation (filter #(= :error (:op-type %)) @traces))))))))

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

(deftest jvm-supersede-still-emits-trace-event
  (testing "re-issuing under a live :request-id aborts the prior attempt with
            one :info :rf.http/aborted row (:reason :request-id-superseded)
            beside its stale-suppressed row"
    (let [latch (CountDownLatch. 1)]
      (capturing-traces
        (fn [traces]
          (with-server (fn [ex]
                         (.await latch 5 TimeUnit/SECONDS)
                         (write-response! ex 200 "application/json" "{\"ok\":true}"))
            (fn [base]
              (rf/reg-event :search/recorder (fn [_ _] {}))
              (rf/reg-event :search/run
                (fn [_ [_ path]]
                  {:fx [[:rf.http/managed {:request    {:url (str base path)}
                                           :request-id :search
                                           :decode     :json
                                           :reply-to   [:search/recorder]}]]}))
              (rf/dispatch-sync [:search/run "/q1"])
              (poll! #(seq (rf.http.managed/in-flight-snapshot)) "first request in flight")
              (rf/dispatch-sync [:search/run "/q2"])
              (poll! #(seq (ops traces :rf.http/aborted)) "supersede row")
              (is (= [[:info :no-recovery :request-id-superseded :search]]
                     (mapv (juxt :op-type :recovery (comp :reason :tags) (comp :request-id :tags))
                           (ops traces :rf.http/aborted))))
              (is (= [:rf.http/request-id-superseded]
                     (mapv #(get-in % [:tags :rf.reply/stale-reason]) (ops traces :rf.http/stale-suppressed))))
              (.countDown latch))))))))

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

;; ---- issuance counters ----------------------------------------------------

(deftest issuance-counter-evicts-on-completion
  (testing "a request-id's issuance counter is evicted when its attempt
            completes, so an app minting unbounded distinct ids does not grow
            the map: each id issues at 1, holds one live counter, then none"
    (rf.http.registry/reset-issuance-counters-for-test!)
    (is (= [[1 1 0] [1 1 0] [1 1 0]]
           (mapv (fn [i]
                   (let [rid      [:fetch-doc i]
                         issuance (rf.http.registry/next-issuance! :frame/counters rid)
                         live     (rf.http.registry/issuance-counter-count)]
                     (rf.http.registry/evict-issuance-on-completion! :frame/counters rid issuance)
                     [issuance live (rf.http.registry/issuance-counter-count)]))
                 (range 3))))))

(deftest issuance-counter-eviction-preserves-live-successor
  (testing "eviction drops a counter only while it still equals the
            completing attempt's issuance, so a superseded attempt's
            completion keeps its live successor's counter; counters are per
            frame"
    (rf.http.registry/reset-issuance-counters-for-test!)
    (let [rid [:search-box :q]
          n1  (rf.http.registry/next-issuance! :frame/counters rid)
          n2  (rf.http.registry/next-issuance! :frame/counters rid)]
      (is (= [1 2] [n1 n2]))
      (rf.http.registry/evict-issuance-on-completion! :frame/counters rid n1)
      (is (= 3 (rf.http.registry/next-issuance! :frame/counters rid))
          "the superseded attempt's eviction skipped, so the counter survived at 2")
      (rf.http.registry/evict-issuance-on-completion! :frame/counters rid 3)
      (is (zero? (rf.http.registry/issuance-counter-count))))
    (rf.http.registry/reset-issuance-counters-for-test!)
    (is (= [1 1 2] [(rf.http.registry/next-issuance! :frame/a :shared)
                    (rf.http.registry/next-issuance! :frame/b :shared)
                    (rf.http.registry/next-issuance! :frame/a :shared)])
        "a sibling frame reusing a raw id runs its own sequence")
    (rf.http.registry/reset-issuance-counters-for-test!)))
