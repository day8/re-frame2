(ns re-frame.http-interceptors-test
  "JVM tests for Spec 014 §Middleware — HTTP interceptor definitions and each
  frame's `:http-interceptors` chain: registration and clearing, chain order
  and frame scope, what a chain error publishes, issue-time chain capture on
  the live transport, and how the final `:status` an `:after` returns picks
  the reply's branch.

  Requests go to an in-process JDK `HttpServer`, so a test asserts on what
  actually reached the wire. Every test that sends a request awaits its REPLY
  before asserting and before its server stops: the reply is dispatched only
  after the `:after` walk returns, so no response outlives its test and lands
  in a neighbour."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.middleware :as rf.http.middleware]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; The fixture pins `:rf/default` as the ambient frame, the frame `send!`
;; issues from unless told otherwise.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- name-chain!
  "Make (or re-make) `frame` naming `ids` as its `:http-interceptors` chain."
  ([ids] (name-chain! :rf/default ids))
  ([frame ids] (rf/make-frame {:id frame :http-interceptors ids})))

(defn- reg-and-name!
  "Register each `[id interceptor-map]` and make `:rf/default` name them, in
  that order."
  [& pairs]
  (doseq [[id m] pairs] (rf/reg-http-interceptor id m))
  (name-chain! (mapv first pairs)))

;; ---- in-process server harness --------------------------------------------

(defn- with-server
  "Run `(f base-url)` against an in-process server answering each request with
  `(handler exchange)`; the server stops when `f` returns."
  [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setHandler (.createContext server "/")
                 (reify HttpHandler (handle [_ ex] (handler ex))))
    (.setExecutor server nil)
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
         (finally (.stop server 0)))))

(defn- write-response! [^HttpExchange ex status body]
  (let [bytes (.getBytes (str body) "UTF-8")]
    (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
    (.sendResponseHeaders ex status (long (count bytes)))
    (with-open [os (.getResponseBody ex)] (.write os bytes))))

(defn- ok! [ex] (write-response! ex 200 "{}"))

(defn- header-of [^HttpExchange ex name]
  (-> ex .getRequestHeaders (.getFirst name)))

(defn- send!
  "Dispatch one `:rf.http/managed` GET of `url` from `frame`, with `extra`
  merged into its args; the reply is appended to that frame's `::replies`."
  ([url] (send! url {} :rf/default))
  ([url extra frame]
   (rf/reg-event ::reply
     (fn [{:keys [db]} [_ reply]] {:db (update db ::replies (fnil conj []) reply)}))
   (rf/reg-event ::load
     (fn [_ [_ args]] {:fx [[:rf.http/managed args]]}))
   (rf/dispatch-sync [::load (merge {:request {:url url} :decode :json :reply-to [::reply]}
                                    extra)]
                     {:frame frame})))

(defn- await-reply!
  "Wait for `frame`'s `n`th reply (1-based) and return it."
  ([n] (await-reply! n :rf/default))
  ([n frame]
   (-> (rf.test-support/poll-until
         #(let [rs (::replies (rf/app-db-value frame))] (when (<= n (count rs)) rs))
         {:timeout-ms 10000 :label "http-interceptors reply"})
       (nth (dec n)))))

(defn- request!
  "Send one request from `frame` and return its reply once it lands."
  ([url] (request! url :rf/default))
  ([url frame]
   (let [n (inc (count (::replies (rf/app-db-value frame))))]
     (send! url {} frame)
     (await-reply! n frame))))

(defn- with-traces
  "Run `(f traces)` with every trace event appended to the atom `traces`."
  [f]
  (let [traces (atom [])
        id     (gensym "http-interceptors-trace-")]
    (rf.trace.tooling/register-listener! id #(swap! traces conj %))
    (try (f traces)
         (finally (rf.trace.tooling/unregister-listener! id)))))

;; ---- a definition is frame-agnostic --------------------------------------

(deftest reg-http-interceptor-is-a-top-level-definition
  (testing "with no frame in scope, reg-http-interceptor registers the
            registrar kind :http-interceptor, and clear removes it with no
            frame either"
    (binding [rf.frame/*current-frame* nil]
      (is (= :shop/auth (rf/reg-http-interceptor :shop/auth {:before identity})))
      (is (= :shop/auth (:id (rf.registrar/lookup :http-interceptor :shop/auth))))
      (is (= :shop/auth (rf/clear :http-interceptor :shop/auth)))
      (is (nil? (rf.registrar/lookup :http-interceptor :shop/auth)))))
  (testing "a :frame in the interceptor map is refused, and the message names
            the :http-interceptors frame-config key"
    (doseq [frame [:rf/default "not-a-keyword" nil]]
      (let [ex (try (rf/reg-http-interceptor :shop/auth {:frame frame :before identity})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data ex))) (pr-str frame))
        (is (str/includes? (str (ex-message ex)) ":http-interceptors"))))
    (is (nil? (rf.registrar/lookup :http-interceptor :shop/auth))
        "nothing was registered")))

;; ---- chain order and frame scope -------------------------------------------

(deftest chain-runs-in-the-frame-vector-order
  (testing "the frame's :http-interceptors vector is the order: :before runs in
            vector order, each seeing the earlier ones' output, :after in
            reverse, and registration order has no effect"
    (let [seen   (atom nil)
          log    (atom [])
          stamp! (fn [id s]
                   (rf/reg-http-interceptor id
                     {:before #(update-in % [:request :headers "X-Chain"] str s)
                      :after  (fn [_ctx resp] (swap! log conj id) resp)}))]
      ;; Registered c, b, a — the reverse of the first vector below.
      (stamp! :c "c") (stamp! :b "b") (stamp! :a "a")
      (with-server
        (fn [ex] (reset! seen (header-of ex "X-Chain")) (ok! ex))
        (fn [base]
          (doseq [[ids before after] [[[:a :b] "ab" [:b :a]]
                                      [[:b :a] "ba" [:a :b]]
                                      [[:a :b :c] "abc" [:c :b :a]]]]
            (testing (pr-str ids)
              (reset! log [])
              (name-chain! ids)
              (request! (str base "/x"))
              (is (= before @seen) ":before in vector order")
              (is (= after @log) ":after in reverse")))
          (testing "re-registering an id replaces its definition in its place"
            (name-chain! [:a :b])
            (stamp! :a "A")
            (request! (str base "/x"))
            (is (= "Ab" @seen))))))))

(deftest a-frame-runs-only-the-chain-it-names
  (testing "a frame naming no chain runs no interceptor, however many
            definitions are registered, and two frames diverge by naming
            different ids"
    (let [seen (atom {})]
      (with-server
        (fn [^HttpExchange ex]
          (swap! seen assoc (.getPath (.getRequestURI ex)) (header-of ex "X-Marker"))
          (ok! ex))
        (fn [base]
          (doseq [who ["user" "admin"]]
            (rf/reg-http-interceptor (keyword who "auth")
              {:before #(assoc-in % [:request :headers "X-Marker"] who)}))
          (name-chain! :user/app [:user/auth])
          (name-chain! :admin/app [:admin/auth])
          (rf/make-frame {:id :plain/app})
          (request! (str base "/from-user") :user/app)
          (request! (str base "/from-admin") :admin/app)
          (request! (str base "/from-plain") :plain/app)
          ;; The nil is assoc'd, so the equality also proves every request arrived.
          (is (= {"/from-user" "user" "/from-admin" "admin" "/from-plain" nil} @seen)))))))

(deftest unregistered-chain-id-fails-at-issue
  (testing "a frame naming an id no definition answers fails the request at
            issue with :rf.error/unregistered-interceptor naming the id; no
            :before runs and nothing reaches the wire"
    (let [hits    (atom 0)
          befores (atom 0)]
      (with-server (fn [ex] (swap! hits inc) (ok! ex))
        (fn [base]
          (reg-and-name! [:known {:before (fn [ctx] (swap! befores inc) ctx)}])
          (name-chain! [:known :never-registered])
          (with-traces
            (fn [traces]
              (send! (str base "/x") {} :rf/default)
              (let [ev   (first (filter #(= :rf.error/fx-handler-exception (:operation %)) @traces))
                    data (ex-data (get-in ev [:tags :exception]))]
                (is (= :rf.error/unregistered-interceptor (:rf.error/id data)))
                (is (= [:never-registered :rf/default] [(:id data) (:frame data)]))
                (is (= [0 0] [@befores @hits]))))))))))

(deftest a-later-image-shadows-a-definition-for-its-frames
  (testing "a frame built on a later image resolves that image's definition of
            the id; a frame without the image keeps the app's"
    (let [app     (rf/image {:id :test/app :select-ns {:include ["re-frame.http-interceptors-test"]}})
          variant (rf/image {:id :test/variant :select-ns {:include ["probe.variant"]}})]
      (rf/reg-http-interceptor :shop/auth {:doc "app" :before identity})
      (rf/reg-http-interceptor :shop/auth {:ns 'probe.variant :doc "variant" :before identity})
      (rf/make-frame {:id :test/app :images [app] :http-interceptors [:shop/auth]})
      (rf/make-frame {:id :test/variant :images [app variant] :http-interceptors [:shop/auth]})
      (is (= [["app"] ["variant"]]
             (mapv #(mapv :doc (rf.http.managed/interceptors-snapshot %))
                   [:test/app :test/variant]))))))

;; ---- chain-error rows echo author data only as the composer allows ----
;;
;; A bad return is usually a slice of the ctx the interceptor was handed, and a
;; throw's message can quote it, so both rows publish author-controlled data
;; twice: in a slot (`:returned` / `:cause`) and in the `:reason` sentence that
;; echoes that slot. On a sensitive request neither copy may carry the secret.

(def ^:private secret "BAD_RETURN_SECRET")

(defn- secret-ctx
  [sensitive?]
  {:sensitive? sensitive?
   :request    {:url     "https://api.example.invalid/v1"
                :headers {"Authorization" secret}}})

(defn- trace-chain!
  "Run the chain walk `walk` (expected to throw) with a trace listener armed.
  Returns `[ex-data-of-the-throw first-trace-event-whose-operation-is-op]`."
  [op walk]
  (with-traces
    (fn [traces]
      (let [ex (try (walk) nil (catch clojure.lang.ExceptionInfo e e))]
        [(ex-data ex) (first (filter #(= op (:operation %)) @traces))]))))

(defn- carries-secret? [v] (str/includes? (pr-str v) secret))

(defn- echo-auth-before [ctx] [(get-in ctx [:request :headers "Authorization"])])
(defn- echo-auth-after [ctx _response] [(get-in ctx [:request :headers "Authorization"])])

(defn- throw-auth-before
  [ctx]
  (throw (ex-info (str "token rejected: " (get-in ctx [:request :headers "Authorization"])) {})))

(defn- mark-sensitive-before [ctx] (assoc-in ctx [:request :sensitive?] true))

(deftest sensitive-bad-return-redacts-returned-and-reason
  (doseq [[label walk]
          [[":before on a request sensitive at the top level"
            #(rf.http.middleware/run-interceptor-chain!
               :rf/default [{:id :probe :before echo-auth-before}] (secret-ctx true))]
           [":after on a sensitive request"
            #(rf.http.middleware/run-after-chain!
               :rf/default [{:id :probe :after echo-auth-after}] (secret-ctx true)
               {:status :ok :value {}})]
           [":before on a request an EARLIER :before marked sensitive"
            #(rf.http.middleware/run-interceptor-chain!
               :rf/default
               [{:id :mark :before mark-sensitive-before}
                {:id :probe :before echo-auth-before}]
               (secret-ctx false))]]]
    (testing label
      (let [[data ev] (trace-chain! :rf.error/http-interceptor-bad-return walk)
            tags      (:tags ev)]
        (is (= :rf.error/http-interceptor-bad-return (:rf.error/id data))
            "a bad return raises as itself, not re-wrapped as interceptor-failed")
        (is (true? (:sensitive? ev)) "the row is stamped sensitive")
        (is (= :probe (:id tags)) "the row names the offending interceptor")
        (is (str/includes? (str (:reason tags)) "`:probe`")
            ":reason still names the offending interceptor")
        (is (= :rf/redacted (:returned tags)) ":returned projects to the sentinel")
        (is (not (carries-secret? tags))
            "no tag carries the secret — :returned and the :reason echo included"))))
  (testing "a nil return on a sensitive request stays visible: nil carries no data"
    (let [[_ ev] (trace-chain! :rf.error/http-interceptor-bad-return
                               #(rf.http.middleware/run-interceptor-chain!
                                  :rf/default [{:id :probe :before (fn [_ctx] nil)}]
                                  (secret-ctx true)))]
      (is (true? (:sensitive? ev)))
      (is (nil? (get-in ev [:tags :returned])))
      (is (str/includes? (str (get-in ev [:tags :reason])) "not nil.")))))

(deftest non-sensitive-bad-return-keeps-returned-and-reason
  (testing "on a request that is not sensitive the row shows what the
  interceptor returned, in :returned and in the :reason sentence"
    (let [echo-url  (fn [ctx] [(get-in ctx [:request :url])])
          [_ ev]    (trace-chain! :rf.error/http-interceptor-bad-return
                                  #(rf.http.middleware/run-interceptor-chain!
                                     :rf/default [{:id :probe :before echo-url}]
                                     (secret-ctx false)))
          tags      (:tags ev)]
      (is (not (true? (:sensitive? ev))) "not stamped sensitive")
      (is (= ["https://api.example.invalid/v1"] (:returned tags)))
      (is (str/includes? (str (:reason tags)) (pr-str ["https://api.example.invalid/v1"]))))))

(deftest sensitive-interceptor-throw-redacts-the-cause-echo-in-reason
  (doseq [[label chain sensitive?]
          [["sensitive at the top level"
            [{:id :probe :before throw-auth-before}] true]
           ;; Sensitivity is recomputed from the ctx at the throw, not the
           ;; flag the chain started with.
           ["an EARLIER :before marked the request sensitive"
            [{:id :mark :before mark-sensitive-before} {:id :probe :before throw-auth-before}] false]]]
    (testing (str label ": neither :cause nor the :reason sentence echoing it
                  carries the throw's secret")
      (let [[data ev] (trace-chain! :rf.error/http-interceptor-failed
                                    #(rf.http.middleware/run-interceptor-chain!
                                       :rf/default chain (secret-ctx sensitive?)))
            tags      (:tags ev)]
        (is (= :rf.error/http-interceptor-failed (:rf.error/id data)))
        (is (true? (:sensitive? ev)))
        (is (= :probe (:interceptor-id tags)))
        (is (= :rf/redacted (:cause tags)))
        (is (str/includes? (str (:reason tags)) "Cause: :rf/redacted"))
        (is (not (carries-secret? tags))))))
  (testing "not sensitive: the :reason sentence keeps the throw's message"
    (let [[_ ev] (trace-chain! :rf.error/http-interceptor-failed
                               #(rf.http.middleware/run-interceptor-chain!
                                  :rf/default [{:id :probe :before throw-auth-before}]
                                  (secret-ctx false)))]
      (is (not (true? (:sensitive? ev))))
      (is (str/includes? (str (get-in ev [:tags :reason]))
                         (str "Cause: token rejected: " secret))))))

;; ---- the chain error core's fx boundary records ------------------------
;;
;; A request-side chain error leaves `:rf.http/managed` as its handler's throw,
;; so core's fx boundary records it again as `:rf.error/fx-handler-exception`,
;; on the dev trace and on the always-on error record, carrying the exception
;; itself, its message, and a `:reason` quoting that message. On a sensitive
;; request that exception must carry the secret no more than the HTTP row does.

(def ^:private managed-url "https://api.example.invalid/v1?user_id=42")

(defn- managed-args
  [sensitive?]
  {:sensitive? sensitive?
   :request    {:url managed-url :headers {"Authorization" secret}}
   :reply-to   [:core-row/reply]})

(defn- core-fx-rows!
  "Dispatch one `:rf.http/managed` request carrying `args` through the
  `:before` interceptors `befores` (`[[id before-fn] …]`), with a trace
  listener and an always-on error listener armed. Returns core's
  `:rf.error/fx-handler-exception` rows as
  `[dev-trace-event always-on-record]`."
  [befores args]
  (let [records  (atom [])
        error-id (gensym "core-fx-row-record-")]
    (rf.http.middleware/clear-all-http-interceptors!)
    (apply reg-and-name! (for [[id before] befores] [id {:before before}]))
    (rf/reg-event :core-row/load (fn [_ _] {:fx [[:rf.http/managed args]]}))
    (rf.error-emit/register-error-listener! error-id #(swap! records conj %))
    (try
      (with-traces
        (fn [traces]
          (rf/dispatch-sync [:core-row/load])
          [(first (filter #(= :rf.error/fx-handler-exception (:operation %)) @traces))
           (first (filter #(= :rf.error/fx-handler-exception (:rf.error/id %)) @records))]))
      (finally
        (rf.error-emit/unregister-error-listener! error-id)))))

(deftest sensitive-chain-error-reaches-core-fx-row-without-the-secret
  (doseq [[label befores error-id data-of]
          [[":before bad return on a request sensitive at the top level"
            [[:probe echo-auth-before]]
            :rf.error/http-interceptor-bad-return
            (fn [d] {:id (:id d) :returned (:returned d)})]
           [":before throw quoting the token on a sensitive request"
            [[:probe throw-auth-before]]
            :rf.error/http-interceptor-failed
            (fn [d] {:id (:interceptor-id d) :cause (:cause d) :url (:url d)})]]]
    (testing label
      (let [[ev record] (core-fx-rows! befores (managed-args true))
            ex          (get-in ev [:tags :exception])]
        (is (= :rf.http/managed (get-in ev [:tags :rf.fx/id]))
            "core's fx boundary records the managed fx's throw")
        (is (= error-id (:rf.error/id (ex-data ex)))
            "the escaping exception keeps the chain error's id")
        (is (= :probe (:id (data-of (ex-data ex))))
            "the escaping exception still names the failing interceptor")
        (is (every? #{:rf/redacted "https://api.example.invalid/v1?user_id=:rf/redacted"}
                    (vals (dissoc (data-of (ex-data ex)) :id)))
            "its data-bearing slots project as the chain error's HTTP row does")
        (is (not (carries-secret? (:tags ev)))
            "no tag of the core row carries the secret: :exception, :exception-message and :reason included")
        (is (some? record) "the always-on record fires")
        (is (not (carries-secret? record))
            "the always-on record carries no secret either")))))

(deftest non-sensitive-chain-error-reaches-core-fx-row-unchanged
  (testing "on a request that is not sensitive the escaping exception shows
  what the interceptor returned or threw, as it does on the chain error's row"
    (let [echo-url (fn [ctx] [(get-in ctx [:request :url])])
          [ev _]   (core-fx-rows! [[:probe echo-url]] (managed-args false))
          data     (ex-data (get-in ev [:tags :exception]))]
      (is (= :rf.error/http-interceptor-bad-return (:rf.error/id data)))
      (is (= [managed-url] (:returned data)))
      (is (str/includes? (str (get-in ev [:tags :reason])) (pr-str [managed-url]))))))

(deftest non-sensitive-chain-error-reaches-core-fx-row-with-denylisted-param-redacted
  (testing "on a request that is not sensitive, a denylisted query param's
  value is redacted in the escaping exception's :url, as the chain error's own
  row redacts it, on core's row and on its always-on record; a param that is
  not denylisted rides verbatim, and so does the throw's message"
    (let [denied   "DENYLISTED_QUERY_SECRET"
          url      (str "https://api.example.invalid/v1?api_key=" denied "&user_id=42")
          redacted "https://api.example.invalid/v1?api_key=:rf/redacted&user_id=42"]
      (with-traces
        (fn [traces]
          (let [[ev record] (core-fx-rows! [[:probe (fn [_] (throw (ex-info "kaboom" {})))]]
                                           {:request {:url url} :reply-to [:core-row/reply]})
                chain-row   (first (filter #(= :rf.error/http-interceptor-failed (:operation %))
                                           @traces))
                data        (ex-data (get-in ev [:tags :exception]))]
            (is (= redacted (get-in chain-row [:tags :url])) "the chain error's own row redacts it")
            (is (true? (:sensitive? chain-row)) "and is stamped sensitive on the denylist hit")
            (is (= :rf.error/http-interceptor-failed (:rf.error/id data)))
            (is (= redacted (:url data)) "the escaping exception carries the URL as that row does")
            (is (= "kaboom" (:cause data)) "the throw's message rides verbatim")
            (is (not (str/includes? (pr-str (:tags ev)) denied))
                "no tag of core's row carries the value: :exception and :rf.fx/args included")
            (is (some? record) "the always-on record fires")
            (is (= redacted (:url (ex-data (:exception record)))))
            (is (not (str/includes? (pr-str record) denied))
                "the always-on record carries no denylisted value"))))))
  (testing "the registration's query-param carriers apply as on the chain
  error's own row: an included name redacts, and an excepted default rides
  verbatim"
    (rf/reg-fx :rf.http/managed
      {:carriers {:query-params {:include ["shop_token"] :except ["sig"]}}}
      rf.http.managed/managed-handler)
    (let [[ev record] (core-fx-rows!
                        [[:probe (fn [_] (throw (ex-info "kaboom" {})))]]
                        {:request  {:url "https://api.example.invalid/v1?shop_token=SHOP&sig=abc"}
                         :reply-to [:core-row/reply]})
          projected   "https://api.example.invalid/v1?shop_token=:rf/redacted&sig=abc"]
      (is (= projected (:url (ex-data (get-in ev [:tags :exception])))))
      (is (= projected (:url (ex-data (:exception record))))))))

;; ---- clearing --------------------------------------------------------------

(deftest clear-takes-no-frame-opts
  (testing "(rf/clear :http-interceptor id {:frame f}) is refused: the kind is
            not frame-scoped"
    (rf/reg-http-interceptor :clr/auth {:before identity})
    (let [ex (try (rf/clear :http-interceptor :clr/auth {:frame :rf/default}) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= [:rf.error/registrar-clear-bad-request :opts-on-a-non-frame-scoped-kind]
             ((juxt :rf.error/id :reason) (ex-data ex)))))
    (is (some? (rf.registrar/lookup :http-interceptor :clr/auth))
        "the refused clear removed nothing")))

(deftest invalid-interceptor-shape-raises
  (testing "reg-http-interceptor rejects a non-fn :before and a map with
            neither :before nor :after"
    (doseq [interceptor-map [{:before "not-a-fn"}
                             {:doc "no fns at all"}]]
      (let [thrown (try (rf/reg-http-interceptor :x interceptor-map)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))
            (pr-str interceptor-map)))))
  (testing "make-frame rejects an :http-interceptors that is not a vector of
            keyword ids"
    (doseq [ids [:shop/auth [:shop/auth "x"] '(:shop/auth)]]
      (let [thrown (try (name-chain! :bad/app ids) nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))
            (pr-str ids))))))

;; ---- what the :before chain sees and leaves behind ------------------------

(deftest before-ctx-carries-resolved-sensitivity
  (testing "each :before receives :sensitive?, the request's sensitivity as
            resolved from the args before the chain ran: true for a top-level
            or a [:request :sensitive?] opt-in, false otherwise"
    (let [seen (atom [])]
      ;; The :before records what it saw, then throws, so no request is sent.
      (reg-and-name! [:capture-sensitive
                      {:before (fn [ctx]
                                 (swap! seen conj (:sensitive? ctx))
                                 (throw (ex-info "stop before the transport" {})))}])
      (rf/reg-event :bsens/load
        (fn [_ [_ args]]
          {:fx [[:rf.http/managed (merge {:request    {:url "https://api.example.invalid/x"}
                                          :on-success nil
                                          :on-failure nil}
                                         args)]]}))
      (rf/dispatch-sync [:bsens/load {}])
      (rf/dispatch-sync [:bsens/load {:sensitive? true}])
      (rf/dispatch-sync [:bsens/load {:request {:url "https://api.example.invalid/x" :sensitive? true}}])
      (is (= [false true true] @seen)
          "plain, top-level opt-in, [:request :sensitive?] opt-in"))))

(deftest before-added-cljs-only-key-trips-degradation-warning
  (testing "a :before that adds a CLJS-only key (:credentials) trips
            :rf.http/cljs-only-key-ignored-on-jvm: the check runs on the
            post-:before request, not the dispatched args"
    (with-traces
      (fn [traces]
        (with-server ok!
          (fn [base]
            (reg-and-name! [:add-credentials
                            {:before #(assoc-in % [:request :credentials] :include)}])
            (request! (str base "/x"))
            (is (some #(and (= :rf.http/cljs-only-key-ignored-on-jvm (:operation %))
                            (= :credentials (get-in % [:tags :key])))
                      @traces))))))))

;; ---- :after on the live transport -----------------------------------------

(deftest motivating-rate-limit-header-parse
  (testing "an :after reads the response headers the server sent from the
            reply's [:meta :headers] and the ctx its own :before stamped, and
            its transform reaches the delivered reply"
    (with-server
      (fn [^HttpExchange ex]
        (-> ex .getResponseHeaders (.set "X-RateLimit-Remaining" "37"))
        (ok! ex))
      (fn [base]
        (reg-and-name! [:rate-limit-parse
                        {:before #(assoc % ::mark :stamped)
                         :after  (fn [ctx resp]
                                   (assoc resp :rate-limit
                                          {:remaining (get-in resp [:meta :headers "x-ratelimit-remaining"])
                                           :mark      (::mark ctx)}))}])
        (let [reply (request! (str base "/items"))]
          (is (= {:remaining "37" :mark :stamped} (:rate-limit reply)))
          (is (= 200 (get-in reply [:meta :status]))))))))

;; ---- chain resolution is at ISSUE time ------------------------------------
;;
;; The live-transport arm of the issue-time contract: a request's response,
;; and every retry attempt, walk the chain captured before its `:before` walk.
;; `re-frame.http-interceptor-chain-capture-cljs-test` pins the capture point
;; on both hosts. The test changes the definitions and the frame's chain from
;; the server's own attempt-1 handler, so it does not depend on elapsed time.

(deftest retry-attempts-keep-the-issue-time-chain-rf2-v3f6
  (testing "a definition or chain change made between attempt 1 and attempt 2
            does not reach the reply, and :before runs once per request, not
            per attempt"
    (let [hits    (atom 0)
          befores (atom 0)
          log     (atom [])]
      (with-server
        (fn [ex]
          (let [n (swap! hits inc)]
            (when (= 1 n)
              ;; Runs while attempt 1 is being answered, so strictly before the
              ;; retry is issued. The server thread carries no ambient frame.
              (rf/reg-http-interceptor :registered-mid-retry
                {:after (fn [ctx resp] (swap! log conj [:registered-mid-retry (::mark ctx)]) resp)})
              (name-chain! [:registered-mid-retry])
              (rf/clear :http-interceptor :issue-time))
            (write-response! ex (if (= 1 n) 500 200) "{}")))
        (fn [base]
          (reg-and-name! [:issue-time
                          {:before (fn [ctx] (swap! befores inc) (assoc ctx ::mark :stamped))
                           :after  (fn [ctx resp] (swap! log conj [:issue-time (::mark ctx)]) resp)}])
          (send! (str base "/retried")
                 {:retry {:on #{:rf.http/http-5xx} :max-attempts 2
                          :backoff {:base-ms 5 :factor 1 :max-ms 10}}}
                 :rf/default)
          (let [reply (await-reply! 1)]
            (is (= :ok (:status reply)) "the retry recovered on attempt 2")
            (is (= 2 @hits) "control: the server saw two attempts")
            (is (= [:registered-mid-retry]
                   (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
                "control: the frame's chain changed between the attempts")
            (is (= 1 @befores) ":before runs once per request, not once per attempt")
            (is (= [[:issue-time :stamped]] @log)
                "the retried attempt's reply walked the chain captured at issue")))))))

;; ---- the final :status picks the branch ------------------------------------
;;
;; Under split addressing the whole `:after` chain runs first, and the FINAL
;; envelope's `:status` picks the target: `:ok` goes to `:on-success`, anything
;; else to `:on-failure`. `:reply-to` addresses both branches, so it receives
;; the final envelope either way.

(def ^:private split {:on-success [::succeeded] :on-failure [::failed]})

(defn- reg-routing-handlers! []
  (doseq [[id handler] [[::succeeded :on-success] [::failed :on-failure]]]
    (rf/reg-event id
      (fn [{:keys [db]} [_ reply]] {:db (update db ::routed (fnil conj []) [handler reply])}))))

(defn- routed!
  "Issue one `:rf.http/managed` GET of `url` whose reply addressing is
  `addressing`, with `extra` merged into its args, and return the
  `[handler reply]` pairs THIS request was routed to once one has landed. A
  request dispatches one reply, so the vector is complete when it is
  non-empty."
  ([url addressing] (routed! url addressing {} {}))
  ([url addressing extra dispatch-opts]
   (reg-routing-handlers!)
   (rf/reg-event ::load-routed (fn [_ [_ args]] {:fx [[:rf.http/managed args]]}))
   (let [routed #(::routed (rf/app-db-value :rf/default) [])
         before (count (routed))]
     (rf/dispatch-sync [::load-routed (merge {:request {:url url} :decode :json} addressing extra)]
                       dispatch-opts)
     (rf.test-support/poll-until #(let [rs (routed)] (when (< before (count rs)) (subvec rs before)))
                                 {:timeout-ms 10000 :label "http-interceptors routed reply"}))))

(defn- handler-and-status [[handler reply]] [handler (:status reply)])

(deftest after-reclassifying-error-to-ok-routes-to-on-success
  (testing "an :after that treats a 404 as empty delivers the :ok envelope and
            its :value to :on-success"
    (with-server #(write-response! % 404 "{}")
      (fn [base]
        (reg-and-name! [:accept-404
                        {:after (fn [_ctx resp]
                                  (if (= 404 (get-in resp [:error :status]))
                                    (-> resp (dissoc :error) (assoc :status :ok :value []))
                                    resp))}])
        (let [routed (routed! (str base "/missing") split)]
          (is (= [[:on-success :ok]] (mapv handler-and-status routed)))
          (is (= [] (:value (second (first routed))))))))))

(deftest any-final-status-but-ok-routes-to-on-failure
  (testing "the else-branch is :on-failure: a status-less final envelope never
            reaches a success handler"
    (with-server ok!
      (fn [base]
        (reg-and-name! [:restatus {:after #(dissoc %2 :status)}])
        (is (= [:on-failure] (mapv first (routed! (str base "/restatus") split))))))))

(deftest after-reclassification-is-terminal
  (testing "an :after that mints a retryable failure kind routes it to
            :on-failure without re-issuing the request"
    (let [hits (atom 0)]
      (with-server (fn [ex] (swap! hits inc) (ok! ex))
        (fn [base]
          (reg-and-name! [:mint-5xx
                          {:after (fn [_ctx resp]
                                    (if (= :ok (:status resp))
                                      (-> resp (dissoc :value :meta)
                                          (assoc :status :error :error {:kind :rf.http/http-5xx :status 503}))
                                      resp))}])
          (let [routed (routed! (str base "/minted") split
                                {:retry {:on #{:rf.http/http-5xx} :max-attempts 3
                                         :backoff {:base-ms 5 :factor 1 :max-ms 10}}}
                                {})]
            (is (= [[:on-failure :error]] (mapv handler-and-status routed)))
            (is (= 1 @hits) "the server saw one attempt")))))))
