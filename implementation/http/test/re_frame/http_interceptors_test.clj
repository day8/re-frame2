(ns re-frame.http-interceptors-test
  "JVM tests for Spec 014 §Middleware — the per-frame HTTP interceptor chain:
  registration and clearing, chain order and frame scope, what a chain error
  publishes, and issue-time chain capture on the live transport.

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
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; The fixture pins `:rf/default` as the ambient frame, so frameless
;; registrations land there; tests that need no scope rebind
;; `*current-frame*` to nil.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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

;; ---- registration needs a frame scope -------------------------------------

(deftest reg-http-interceptor-bare-fails-closed-with-frame-installs-rf2-9ynwvx
  (testing "a bare reg under no frame scope raises :rf.error/no-frame-context
            and installs nothing; inside (with-frame f …) it installs on f's
            chain even though f was never made (the RealWorld example pattern)"
    (binding [rf.frame/*current-frame* nil]
      (let [thrown (try (rf/reg-http-interceptor :realworld/bearer-auth {:before identity})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/no-frame-context (:rf.error/id (ex-data thrown))))
        (is (empty? (rf.http.managed/interceptors-snapshot :rf/default))
            "nothing was installed on a synthesised :rf/default chain"))
      (rf/with-frame :realworld/app
        (rf/reg-http-interceptor :realworld/bearer-auth {:before identity})))
    (is (= [:realworld/bearer-auth]
           (mapv :id (rf.http.managed/interceptors-snapshot :realworld/app))))))

;; ---- chain order and frame scope -------------------------------------------

(deftest multi-interceptor-runs-in-registration-order
  (testing "the :before chain runs in registration order, each :before seeing
            the earlier ones' output, and its final request is what reaches the
            wire; re-registering an id replaces it in place, while clearing and
            then re-registering it appends it at the end"
    (let [seen   (atom nil)
          stamp! (fn [id s]
                   (rf/reg-http-interceptor id
                     {:before #(update-in % [:request :headers "X-Chain"] str s)}))]
      (with-server
        (fn [ex] (reset! seen (header-of ex "X-Chain")) (ok! ex))
        (fn [base]
          (doseq [[label register! expected]
                  [["registration order"
                    #(do (stamp! :a "a") (stamp! :b "b") (stamp! :c "c"))
                    "abc"]
                   ["re-registering an id replaces it in place"
                    #(do (stamp! :a "1") (stamp! :b "b") (stamp! :a "2"))
                    "2b"]
                   ["clear then re-register appends at the end"
                    #(do (stamp! :a "a") (stamp! :b "b") (stamp! :c "c")
                         (rf/clear :http-interceptor :a)
                         (stamp! :a "a"))
                    "bca"]]]
            (testing label
              (rf.http.middleware/clear-all-http-interceptors!)
              (register!)
              (request! (str base "/x"))
              (is (= expected @seen)))))))))

(deftest interceptor-is-frame-scoped
  (testing "an interceptor registered on frame A does not transform frame B's requests"
    (let [seen (atom {})]
      (with-server
        (fn [^HttpExchange ex]
          (swap! seen assoc (.getPath (.getRequestURI ex)) (header-of ex "X-Marker"))
          (ok! ex))
        (fn [base]
          (rf/make-frame {:id :other-frame :doc "alt frame"})
          (rf/reg-http-interceptor :marker
            {:frame  :rf/default
             :before #(assoc-in % [:request :headers "X-Marker"] "default-only")})
          (let [default-reply (request! (str base "/from-default"))
                other-reply   (request! (str base "/from-other") :other-frame)]
            ;; Both replies must be successes, and both requests must have been
            ;; observed, so the nil below reads "carried no header", never
            ;; "never arrived".
            (is (= :ok (:status default-reply)) "default-frame reply is a success")
            (is (= :ok (:status other-reply)) "other-frame reply is a success")
            (is (= #{"/from-default" "/from-other"} (set (keys @seen)))
                "both requests reached the header-observing handler")
            (is (= "default-only" (get @seen "/from-default"))
                "default-frame request carried the interceptor's header")
            (is (nil? (get @seen "/from-other"))
                "other-frame request did NOT carry the header — interceptor is frame-scoped")))))))

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
    (doseq [[id before] befores]
      (rf/reg-http-interceptor id {:before before}))
    (rf/reg-event :core-row/load (fn [_ _] {:fx [[:rf.http/managed args]]}))
    (rf.error-emit/register-error-listener! error-id #(swap! records conj %))
    (try
      (with-traces
        (fn [traces]
          (rf/dispatch-sync [:core-row/load])
          [(first (filter #(= :rf.error/fx-handler-exception (:operation %)) @traces))
           (first (filter #(= :rf.error/fx-handler-exception (:error %)) @records))]))
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
      (is (str/includes? (str (get-in ev [:tags :reason])) (pr-str [managed-url])))))
  (testing "a throw's message rides the escaping exception verbatim"
    (let [[ev _] (core-fx-rows! [[:probe throw-auth-before]] (managed-args false))
          data   (ex-data (get-in ev [:tags :exception]))]
      (is (= :rf.error/http-interceptor-failed (:rf.error/id data)))
      (is (= (str "token rejected: " secret) (:cause data)))
      (is (= managed-url (:url data))))))

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

(deftest clear-http-interceptor-single-arity-fails-closed-under-no-scope
  (testing "no-opts (rf/clear :http-interceptor id) under no ambient frame
            raises :rf.error/no-frame-context rather than clearing against a
            synthesised :rf/default"
    (binding [rf.frame/*current-frame* nil]
      (let [thrown (try (rf/clear :http-interceptor :some-id)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/no-frame-context (:rf.error/id (ex-data thrown))))))))

(deftest clear-http-interceptor-frame-arg-spelling-rf2-f28bno
  (testing "(rf/clear :http-interceptor id {:frame f}) clears f's slot from an
            ambient :rf/default scope and leaves the ambient chain alone"
    (rf/reg-http-interceptor :fa/on-other {:frame :fa/other :before identity})
    (rf/reg-http-interceptor :fa/on-default {:before identity})
    (is (= [:fa/on-other] (mapv :id (rf.http.managed/interceptors-snapshot :fa/other)))
        "control: the named frame's slot exists before the clear")
    (rf/clear :http-interceptor :fa/on-other {:frame :fa/other})
    (is (= [[] [:fa/on-default]]
           (mapv #(mapv :id (rf.http.managed/interceptors-snapshot %)) [:fa/other :rf/default])))))

;; The opts map is EXACTLY `{:frame target}`. A malformed one fails closed
;; before any ambient state is touched — an `(or (:frame opts) ambient-frame)`
;; resolution would silently clear the ambient frame instead. There are two
;; doors with their own typed errors over one shared validator
;; (`re-frame.frame/frame-opts?`): `rf/clear` itself raises
;; `:rf.error/registrar-clear-bad-request`, and the artefact-level fn the
;; `:http/clear-http-interceptor` hook reaches raises
;; `:rf.error/http-bad-interceptor`. One row per validator clause.

(deftest clear-http-interceptor-opts-form-fail-closed-rf2-s32bf
  (testing "malformed opts fail closed at BOTH doors and leave the ambient
            interceptor untouched; the exact {:frame target} form clears"
    (letfn [(threw-with? [error-id thunk]
              (let [ex (try (thunk) nil
                            (catch clojure.lang.ExceptionInfo e e))]
                (= error-id (:rf.error/id (ex-data ex)))))]
      (rf/reg-http-interceptor :s32bf/ambient {:before identity})
      (is (= [:s32bf/ambient]
             (mapv :id (rf.http.managed/interceptors-snapshot :rf/default))))
      (doseq [[label opts]
              [["misspelled opts key"                          {:fram :rf/default}]
               ["nil :frame"                                   {:frame nil}]
               ["two-scalar frame-first is not a public shape" :some-frame]]]
        (testing label
          (is (threw-with? :rf.error/registrar-clear-bad-request
                           #(rf/clear :http-interceptor :s32bf/ambient opts))
              "public door")
          (is (threw-with? :rf.error/http-bad-interceptor
                           #(rf.http.middleware/clear-http-interceptor :s32bf/ambient opts))
              "artefact door")))
      (is (= [:s32bf/ambient]
             (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
          "no malformed clear touched the ambient :rf/default interceptor")
      (rf/clear :http-interceptor :s32bf/ambient {:frame :rf/default})
      (is (empty? (rf.http.managed/interceptors-snapshot :rf/default))
          "the exact {:frame target} form clears the named frame"))))

(deftest invalid-interceptor-shape-raises
  (testing "reg-http-interceptor rejects a non-fn :before, a map with neither
            :before nor :after, and a non-keyword :frame"
    (doseq [interceptor-map [{:before "not-a-fn"}
                             {:doc "no fns at all"}
                             {:frame "not-a-keyword" :before identity}]]
      (let [thrown (try (rf/reg-http-interceptor :x interceptor-map)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))
            (pr-str interceptor-map))))))

;; ---- what the :before chain sees and leaves behind ------------------------

(deftest before-ctx-carries-resolved-sensitivity
  (testing "each :before receives :sensitive?, the request's sensitivity as
            resolved from the args before the chain ran: true for a top-level
            or a [:request :sensitive?] opt-in, false otherwise"
    (let [seen (atom [])]
      ;; The :before records what it saw, then throws, so no request is sent.
      (rf/reg-http-interceptor :capture-sensitive
        {:before (fn [ctx]
                   (swap! seen conj (:sensitive? ctx))
                   (throw (ex-info "stop before the transport" {})))})
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
            (rf/reg-http-interceptor :add-credentials
              {:before #(assoc-in % [:request :credentials] :include)})
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
        (rf/reg-http-interceptor :rate-limit-parse
          {:before #(assoc % ::mark :stamped)
           :after  (fn [ctx resp]
                     (assoc resp :rate-limit
                            {:remaining (get-in resp [:meta :headers "x-ratelimit-remaining"])
                             :mark      (::mark ctx)}))})
        (let [reply (request! (str base "/items"))]
          (is (= {:remaining "37" :mark :stamped} (:rate-limit reply)))
          (is (= 200 (get-in reply [:meta :status]))))))))

;; ---- chain resolution is at ISSUE time ------------------------------------
;;
;; The live-transport arm of the issue-time contract: a request's response,
;; and every retry attempt, walk the chain captured before its `:before` walk.
;; `re-frame.http-interceptor-chain-capture-cljs-test` pins the capture point
;; on both hosts. Neither test below depends on elapsed time: the first holds
;; its response on a latch the test releases, the second mutates the registry
;; from the server's own attempt-1 handler.

(deftest live-response-walks-its-issue-time-chain-rf2-v3f6
  (testing "a response held open across a registry change walks the chain its
            request was issued under: the cleared :after still runs, holding the
            ctx its own :before stamped, and one registered mid-flight does not"
    (let [arrived (CountDownLatch. 1)
          release (CountDownLatch. 1)
          log     (atom [])]
      (with-server
        (fn [ex]
          (.countDown arrived)
          (.await release 10 TimeUnit/SECONDS)
          (ok! ex))
        (fn [base]
          (try
            (rf/reg-http-interceptor :issue-time
              {:before #(assoc % ::mark :stamped)
               :after  (fn [ctx resp] (swap! log conj [:issue-time (::mark ctx)]) resp)})
            (send! (str base "/held"))
            (is (.await arrived 10 TimeUnit/SECONDS)
                "control: the request reached the server, so its chain capture has happened")
            (rf/clear :http-interceptor :issue-time)
            (rf/reg-http-interceptor :registered-mid-flight
              {:after (fn [ctx resp] (swap! log conj [:registered-mid-flight (::mark ctx)]) resp)})
            (is (= [:registered-mid-flight]
                   (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
                "control: the live registry changed while the response was outstanding")
            (.countDown release)
            (await-reply! 1)
            (is (= [[:issue-time :stamped]] @log))
            (finally (.countDown release))))))))

(deftest retry-attempts-keep-the-issue-time-chain-rf2-v3f6
  (testing "a registry change made between attempt 1 and attempt 2 does not
            reach the reply, and :before runs once per request, not per attempt"
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
                {:frame :rf/default
                 :after (fn [ctx resp] (swap! log conj [:registered-mid-retry (::mark ctx)]) resp)})
              (rf/clear :http-interceptor :issue-time {:frame :rf/default}))
            (write-response! ex (if (= 1 n) 500 200) "{}")))
        (fn [base]
          (rf/reg-http-interceptor :issue-time
            {:before (fn [ctx] (swap! befores inc) (assoc ctx ::mark :stamped))
             :after  (fn [ctx resp] (swap! log conj [:issue-time (::mark ctx)]) resp)})
          (send! (str base "/retried")
                 {:retry {:on #{:rf.http/http-5xx} :max-attempts 2
                          :backoff {:base-ms 5 :factor 1 :max-ms 10}}}
                 :rf/default)
          (let [reply (await-reply! 1)]
            (is (= :ok (:status reply)) "the retry recovered on attempt 2")
            (is (= 2 @hits) "control: the server saw two attempts")
            (is (= [:registered-mid-retry]
                   (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
                "control: the registry changed between the attempts")
            (is (= 1 @befores) ":before runs once per request, not once per attempt")
            (is (= [[:issue-time :stamped]] @log)
                "the retried attempt's reply walked the chain captured at issue")))))))
