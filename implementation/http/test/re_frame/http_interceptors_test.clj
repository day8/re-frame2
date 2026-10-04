(ns re-frame.http-interceptors-test
  "JVM tests for Spec 014 §Middleware — per-frame request interceptor
  chain.

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
           [java.net InetSocketAddress]))

;; ---- per-test reset --------------------------------------------------------

;; EP-0002: reg-http-interceptor / clear-http-interceptor are
;; context-required frame-local, and these tests dispatch managed HTTP to
;; exercise the chain — a bare call raises :rf.error/no-frame-context. The
;; canonical fixture's default `:ambient-frame :rf/default` pins :rf/default
;; as the established scope, so frameless registrations land there and
;; managed-HTTP dispatches drain; tests targeting an explicit frame pass
;; {:frame …}, and the fails-closed test rebinds *current-frame* to nil
;; locally. The canonical post-dispose reset also clears the per-frame
;; HTTP interceptor chain, so no explicit clear-all is needed.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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
  "Thin alias over `test-support/poll-until` with
  the per-file `(pred db)` arity that read sites here expect."
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until
     #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "http-interceptors reply"})))

;; ---- TEST ISOLATION: await the RESPONSE, never just the request ------------
;;
;; Every test below drives a REAL in-process HTTP round trip. A test that
;; returns while its own response is still in flight leaks that response into
;; whichever test runs next — it tears its server down under a live request,
;; and its reply lands during a neighbour's body. Test order is `ns-interns`
;; hash order, so which pair collides moves whenever a test is added or
;; deleted; the leak presents as an unrelated test flaking.
;;
;; THE RUNTIME CLOSES THE WORST FORM OF THAT LEAK: the reply tail
;; (`middleware/run-after-then-dispatch!`) walks the `:after` chain captured
;; at issue and carried with the ctx, never the LIVE per-frame registry at
;; RESPONSE time, so a leaked response walks its OWN chain and cannot fire a
;; neighbour's interceptors with a middleware-ctx they never produced. The
;; response-side wait is still needed: it keeps `stop-server!` from
;; running under a live request and a stray reply from landing mid-neighbour.
;;
;; Two request-side waits look sufficient and are NOT:
;;   * polling until the `:before` fns have fired — they run synchronously
;;     inside `dispatch-sync`, so `poll-until`'s first probe succeeds before
;;     the round trip has even begun; and
;;   * polling a latch the server handler sets — the handler sets it BEFORE
;;     `write-response!`.
;;
;; The sufficient wait is the REPLY landing in app-db: the reply is dispatched
;; only after `run-after-chain!` has returned for that request. So every test
;; that starts a server sends its request with a `:reply-to` and awaits the
;; reply before it asserts and before `stop-server!`.
;;
;; Under a request-side wait the response has typically not completed by
;; `stop-server!` in `clear-then-reg-appends-to-end-of-chain` and
;; `re-registering-id-replaces-slot`, and running either immediately before
;; `after-less-interceptors-are-transparent` intermittently corrupts that
;; test's `:after` order. The response-side wait prevents both.

;; ---- 1a. reg within with-frame installs; bare reg fails closed -----------
;;
;; A BARE top-level
;; `(reg-http-interceptor id {:before …})` in an app's boot `run` — no ambient
;; frame scope, no `:frame` override — raises the always-on
;; `:rf.error/no-frame-context` (EP-0002 context-required frame-local) and
;; installs NOTHING, so every authenticated request would silently lose its
;; `Authorization` header. Scoping the registration to the app frame
;; with `(with-frame :rf/default …)` installs it, as the RealWorld example
;; apps (examples/real-apps/realworld_{http,resources}) do. This test pins BOTH halves of that
;; contract end-to-end — the fixture's ambient `*current-frame* :rf/default`
;; masks the bare-call raise, so we strip it with `binding … nil`:
;;   (a) a bare reg under no scope fails closed and installs nothing;
;;   (b) `(with-frame :rf/default …)` installs on the frame's chain AND the
;;       `:before` reaches the wire — an authenticated request carries the
;;       `Authorization` header the interceptor stamped.

(deftest reg-within-with-frame-installs-and-header-reaches-wire-rf2-9ynwvx
  (testing "bare reg under no scope raises + installs nothing; a
            (with-frame :rf/default …) registration lands the Authorization
            header on the wire (the RealWorld example bearer-auth pattern)"
    (let [seen-auth (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (reset! seen-auth (header-of ex "Authorization"))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        ;; (a) bare reg under NO ambient scope
        ;; fails closed and installs nothing.
        (binding [rf.frame/*current-frame* nil]
          (let [thrown (try (rf/reg-http-interceptor :realworld/bearer-auth
                              {:before identity})
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (some? thrown)
                "a bare reg-http-interceptor under no frame scope must throw")
            (is (= :rf.error/no-frame-context (:rf.error/id (ex-data thrown)))
                "the throw is the always-on :rf.error/no-frame-context")
            (is (empty? (rf.http.managed/interceptors-snapshot :rf/default))
                "nothing was installed on the :rf/default chain"))
          ;; (b) with-frame supplies the frame context, so the reg
          ;; installs on :rf/default even under no ambient scope.
          (rf/with-frame :rf/default
            (rf/reg-http-interceptor :realworld/bearer-auth
              {:before (fn [ctx]
                         (assoc-in ctx [:request :headers "Authorization"]
                                   "Token stub.demo.jwt"))})))
        (is (= [:realworld/bearer-auth]
               (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
            "with-frame scoped the reg onto the app frame's chain")
        ;; (c) an authenticated request from that frame carries the header the
        ;; interceptor stamped — proving the scoped reg actually decorates the wire.
        (rf/reg-event :load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:load msg] :request {:url (str "http://127.0.0.1:" port "/secured")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:load])
        (await-reply! #(some? (:reply %)) 5000)
        (is (= "Token stub.demo.jwt" @seen-auth)
            "the with-frame-registered interceptor stamped Authorization on the wire")
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
        (rf/reg-http-interceptor :first
          {:before (fn [ctx]
                     (swap! order conj :first)
                     (assoc-in ctx [:request :headers "X-One"] "1"))})
        (rf/reg-http-interceptor :second
          {:before (fn [ctx]
                     (swap! order conj :second)
                     ;; verify earlier interceptor's output is visible
                     (is (= "1" (get-in ctx [:request :headers "X-One"])))
                     (assoc-in ctx [:request :headers "X-Two"] "2"))})
        (rf/reg-http-interceptor :third
          {:before (fn [ctx]
                     (swap! order conj :third)
                     (is (= "2" (get-in ctx [:request :headers "X-Two"])))
                     (assoc-in ctx [:request :headers "X-Three"] "3"))})
        (rf/reg-event :load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:load msg] :request {:url (str "http://127.0.0.1:" port "/x")}
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
    ;; The two "seen-on-*" atoms hold the X-Marker header value the server
    ;; observed. Readiness is each frame's REPLY landing in its own app-db —
    ;; header values are NOT a valid readiness signal here, because
    ;; :other-frame's request is *expected* to carry a nil header, which is
    ;; indistinguishable from "request has not yet landed".
    ;;
    ;; But that same ambiguity makes the reply-landed wait insufficient on its
    ;; OWN as the whole readiness story. A transport / error reply
    ;; satisfies it just as well as a success, so a request that never reached
    ;; the header observer at all leaves `seen-on-other` at its initial nil and
    ;; the central `(nil? @seen-on-other)` assertion passes VACUOUSLY — the
    ;; precise condition this test's own comment claims to exclude. Two
    ;; witnesses close it, and they close different holes: `observed` records
    ;; that each handler branch actually RAN (so nil means "observed, no
    ;; header", never "never observed" — this is the half that survives a
    ;; server that answers 200 on a path the cond does not match), and the
    ;; per-reply `:status` assertions below require the expected SUCCESS rather
    ;; than merely some reply. Neither replaces the wait: it is what keeps this
    ;; test's own responses from outliving `stop-server!`.
    (let [seen-on-default (atom nil)
          seen-on-other   (atom nil)
          observed        (atom #{})
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (let [path (.getPath (.getRequestURI ex))]
                (cond
                  (.startsWith path "/from-default")
                  (do (swap! observed conj :default)
                      (reset! seen-on-default (header-of ex "X-Marker")))

                  (.startsWith path "/from-other")
                  (do (swap! observed conj :other)
                      (reset! seen-on-other (header-of ex "X-Marker"))))
                (write-response! ex 200 "application/json" "{}"))))]
      (try
        (rf/make-frame {:id :other-frame :doc "alt frame"})
        ;; Register interceptor ONLY on :rf/default. :other-frame has none.
        (rf/reg-http-interceptor :marker
          {:frame  :rf/default
           :before (fn [ctx]
                     (assoc-in ctx [:request :headers "X-Marker"] "default-only"))})
        ;; Two events — one on each frame — both fire :rf.http/managed, each
        ;; carrying a :reply-to so the response side is observable (see the
        ;; §TEST ISOLATION note above).
        (rf/reg-event :load-default
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:load-default msg]
                      :request  {:url (str "http://127.0.0.1:" port "/from-default")}
                      :decode   :json}]]})))
        (rf/reg-event :load-other
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:load-other msg]
                      :request  {:url (str "http://127.0.0.1:" port "/from-other")}
                      :decode   :json}]]})))
        (rf/dispatch-sync [:load-default])
        (rf/dispatch-sync [:load-other] {:frame :other-frame})
        ;; Await BOTH replies. A server-side "the request landed" latch is set
        ;; BEFORE `write-response!`, so returning on one leaves this test's own
        ;; responses in flight past `stop-server!`.
        (rf.test-support/poll-until
          #(and (some? (:reply (rf/app-db-value :rf/default)))
                (some? (:reply (rf/app-db-value :other-frame))))
          {:timeout-ms 5000 :label "both frames' replies landed"})
        ;; Both replies must be the EXPECTED SUCCESS, not merely present: an
        ;; error envelope satisfies the wait above without either request
        ;; having reached the header observer.
        (is (= :ok (:status (:reply (rf/app-db-value :rf/default))))
            "default-frame reply is a success, not a transport/error envelope")
        (is (= :ok (:status (:reply (rf/app-db-value :other-frame))))
            "other-frame reply is a success, not a transport/error envelope")
        ;; …and both header observers must actually have run, so the nil below
        ;; reads "observed, carried no header" rather than "never observed".
        (is (= #{:default :other} @observed)
            "both requests reached their header-observing handler branch")
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
        (rf.trace.tooling/register-listener! listener-id
                                  (fn [ev] (swap! traces conj ev)))
        (rf/reg-http-interceptor :boom
          {:before (fn [_ctx]
                     (throw (ex-info "kaboom" {:detail :synthetic})))})
        (rf/reg-event :load
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
        ;; Wait for the interceptor-failure trace to land —
        ;; the trace event IS the observable signal, and `poll-until` throws
        ;; on timeout, so this wait is the trace assertion. server-hits is
        ;; then asserted as zero (proven absence within the trace-fired window).
        (rf.test-support/poll-until
          #(some (fn [t] (= :rf.error/http-interceptor-failed (:operation t)))
                 @traces)
          {:label ":rf.error/http-interceptor-failed surfaced"})
        (is (zero? @server-hits)
            "request was NOT dispatched — server saw zero requests")
        (finally
          (rf.trace.tooling/unregister-listener! listener-id)
          (stop-server! srv))))))

;; ---- 4a. interceptor-failure URL redaction --------------------------------

(deftest interceptor-failure-trace-redacts-denylisted-query-params
  (testing "when a
  `:before` throws, the `:rf.error/http-interceptor-failed` trace MUST
  route the request URL through the privacy composer; a
  raw URL on the trace surface would leak any denylisted query param
  (`?api_key=…`) into trace consumers."
    (let [traces      (atom [])
          listener-id (gensym "interceptor-redact-")]
      (try
        (rf.trace.tooling/register-listener! listener-id
                                  (fn [ev] (swap! traces conj ev)))
        (rf/reg-http-interceptor :boom
          {:before (fn [_ctx]
                     (throw (ex-info "kaboom" {:detail :synthetic})))})
        (rf/reg-event :load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url "https://api.example.invalid/v1?api_key=SECRET&page=2"}
                    :decode  :json
                    :on-success nil
                    :on-failure nil}]]}))
        (rf/dispatch-sync [:load])
        ;; Wait for the redacted-trace event to land.
        (rf.test-support/poll-until
          #(some (fn [t] (= :rf.error/http-interceptor-failed (:operation t)))
                 @traces)
          {:label ":rf.error/http-interceptor-failed surfaced (redacted variant)"})
        (let [w (first (filter #(= :rf.error/http-interceptor-failed
                                    (:operation %))
                                @traces))]
          (is (some? w) ":rf.error/http-interceptor-failed should be on the stream")
          (let [tags (:tags w)]
            (is (= "https://api.example.invalid/v1?api_key=:rf/redacted&page=2"
                   (:url tags))
                "denylisted query-param value MUST be scrubbed")
            (is (true? (:sensitive? w))
                ":sensitive? stamped on the trace (denylist hit = signal)")))
        (finally
          (rf.trace.tooling/unregister-listener! listener-id))))))

;; ---- 4b. a non-map return is its own error, distinct from a throw ---------

(defn- run-one-before!
  "Walk a one-interceptor `:before` chain directly and return
  `[ex-data-of-the-throw trace-operations]`."
  [before]
  (let [traces      (atom [])
        listener-id (gensym "interceptor-bad-return-")]
    (rf.trace.tooling/register-listener! listener-id
                                         (fn [ev] (swap! traces conj ev)))
    (try
      (let [ex (try
                 (rf.http.middleware/run-interceptor-chain!
                   :rf/default
                   [{:id :probe :before before}]
                   {:request {:url "https://api.example.invalid/v1"}})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        [(ex-data ex) (mapv :operation @traces)])
      (finally
        (rf.trace.tooling/unregister-listener! listener-id)))))

(deftest before-returning-nil-emits-bad-return-not-failed
  (testing "a :before that returns nil raises and traces
  :rf.error/http-interceptor-bad-return as itself; it is not re-wrapped as
  :rf.error/http-interceptor-failed, which is reserved for a throw"
    (let [[data ops] (run-one-before! (fn [_ctx] nil))]
      (is (= :rf.error/http-interceptor-bad-return (:rf.error/id data))
          "the raised error is the bad-return id")
      (is (= :probe (:id data)))
      (is (and (contains? data :returned) (nil? (:returned data)))
          ":returned carries the offending value")
      (is (some #{:rf.error/http-interceptor-bad-return} ops)
          ":rf.error/http-interceptor-bad-return reaches the trace")
      (is (not (some #{:rf.error/http-interceptor-failed} ops))
          ":rf.error/http-interceptor-failed does NOT fire for a bad return"))))

(deftest before-that-throws-still-emits-failed
  (testing "a :before that throws still raises and traces
  :rf.error/http-interceptor-failed, and emits no bad-return"
    (let [[data ops] (run-one-before!
                       (fn [_ctx] (throw (ex-info "kaboom" {:detail :synthetic}))))]
      (is (= :rf.error/http-interceptor-failed (:rf.error/id data)))
      (is (some #{:rf.error/http-interceptor-failed} ops))
      (is (not (some #{:rf.error/http-interceptor-bad-return} ops))))))

;; ---- 4c. chain-error rows echo author data only as the composer allows ----
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
  (let [traces      (atom [])
        listener-id (gensym "chain-error-privacy-")]
    (rf.trace.tooling/register-listener! listener-id
                                         (fn [ev] (swap! traces conj ev)))
    (try
      (let [ex (try (walk) nil (catch clojure.lang.ExceptionInfo e e))]
        [(ex-data ex) (first (filter #(= op (:operation %)) @traces))])
      (finally
        (rf.trace.tooling/unregister-listener! listener-id)))))

(defn- carries-secret? [v] (str/includes? (pr-str v) secret))

(defn- echo-auth-before [ctx] [(get-in ctx [:request :headers "Authorization"])])
(defn- echo-auth-after [ctx _response] [(get-in ctx [:request :headers "Authorization"])])

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
               [{:id :mark :before (fn [ctx] (assoc-in ctx [:request :sensitive?] true))}
                {:id :probe :before echo-auth-before}]
               (secret-ctx false))]]]
    (testing label
      (let [[data ev] (trace-chain! :rf.error/http-interceptor-bad-return walk)
            tags      (:tags ev)]
        (is (= :rf.error/http-interceptor-bad-return (:rf.error/id data))
            "the raised error is still the bad-return id")
        (is (some? ev) "the bad-return row reaches the trace")
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
  (let [throw-secret (fn [ctx]
                       (throw (ex-info (str "token rejected: "
                                            (get-in ctx [:request :headers "Authorization"]))
                                       {})))
        walk         (fn [sensitive?]
                       #(rf.http.middleware/run-interceptor-chain!
                          :rf/default [{:id :probe :before throw-secret}]
                          (secret-ctx sensitive?)))]
    (testing "sensitive: neither :cause nor the :reason sentence echoing it
    carries the throw's secret"
      (let [[data ev] (trace-chain! :rf.error/http-interceptor-failed (walk true))
            tags      (:tags ev)]
        (is (= :rf.error/http-interceptor-failed (:rf.error/id data)))
        (is (true? (:sensitive? ev)))
        (is (= :probe (:interceptor-id tags)))
        (is (= :rf/redacted (:cause tags)))
        (is (str/includes? (str (:reason tags)) "Cause: :rf/redacted"))
        (is (not (carries-secret? tags)))))
    (testing "not sensitive: the :reason sentence keeps the throw's message"
      (let [[_ ev] (trace-chain! :rf.error/http-interceptor-failed (walk false))]
        (is (not (true? (:sensitive? ev))))
        (is (str/includes? (str (get-in ev [:tags :reason]))
                           (str "Cause: token rejected: " secret)))))))

;; ---- 4d. the chain error core's fx boundary records ------------------------
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

(defn- throw-auth-before
  [ctx]
  (throw (ex-info (str "token rejected: " (get-in ctx [:request :headers "Authorization"])) {})))

(defn- mark-sensitive-before [ctx] (assoc-in ctx [:request :sensitive?] true))

(defn- core-fx-rows!
  "Dispatch one `:rf.http/managed` request carrying `args` through the
  `:before` interceptors `befores` (`[[id before-fn] …]`), with a trace
  listener and an always-on error listener armed. Returns core's
  `:rf.error/fx-handler-exception` rows as
  `[dev-trace-event always-on-record]`."
  [befores args]
  (let [traces   (atom [])
        records  (atom [])
        trace-id (gensym "core-fx-row-trace-")
        error-id (gensym "core-fx-row-record-")]
    ;; Each call starts from an empty chain, so `befores` is the chain's order.
    (rf.http.middleware/clear-all-http-interceptors!)
    (doseq [[id before] befores]
      (rf/reg-http-interceptor id {:before before}))
    (rf/reg-event :core-row/load (fn [_ _] {:fx [[:rf.http/managed args]]}))
    (rf.trace.tooling/register-listener! trace-id #(swap! traces conj %))
    (rf.error-emit/register-error-listener! error-id #(swap! records conj %))
    (try
      (rf/dispatch-sync [:core-row/load])
      [(first (filter #(= :rf.error/fx-handler-exception (:operation %)) @traces))
       (first (filter #(= :rf.error/fx-handler-exception (:error %)) @records))]
      (finally
        (rf.trace.tooling/unregister-listener! trace-id)
        (rf.error-emit/unregister-error-listener! error-id)))))

(deftest sensitive-chain-error-reaches-core-fx-row-without-the-secret
  (doseq [[label befores args error-id data-of]
          [[":before bad return on a request sensitive at the top level"
            [[:probe echo-auth-before]] (managed-args true)
            :rf.error/http-interceptor-bad-return
            (fn [d] {:id (:id d) :returned (:returned d)})]
           [":before throw quoting the token on a sensitive request"
            [[:probe throw-auth-before]] (managed-args true)
            :rf.error/http-interceptor-failed
            (fn [d] {:id (:interceptor-id d) :cause (:cause d) :url (:url d)})]
           [":before bad return after an EARLIER :before marked the request sensitive"
            [[:mark mark-sensitive-before] [:probe echo-auth-before]] (managed-args false)
            :rf.error/http-interceptor-bad-return
            (fn [d] {:id (:id d) :returned (:returned d)})]]]
    (testing label
      (let [[ev record] (core-fx-rows! befores args)
            ex          (get-in ev [:tags :exception])]
        (is (some? ev) "core's fx boundary records the managed fx's throw")
        (is (= :rf.http/managed (get-in ev [:tags :rf.fx/id])))
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
    (let [denied      "DENYLISTED_QUERY_SECRET"
          url         (str "https://api.example.invalid/v1?api_key=" denied "&user_id=42")
          redacted    "https://api.example.invalid/v1?api_key=:rf/redacted&user_id=42"
          traces      (atom [])
          listener-id (gensym "denylist-chain-row-")
          _           (rf.trace.tooling/register-listener! listener-id #(swap! traces conj %))
          [ev record] (try
                        (core-fx-rows! [[:probe (fn [_] (throw (ex-info "kaboom" {})))]]
                                       {:request {:url url} :reply-to [:core-row/reply]})
                        (finally (rf.trace.tooling/unregister-listener! listener-id)))
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
          "the always-on record carries no denylisted value")))
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
        (rf/reg-http-interceptor :auth-header
          {:before (fn [ctx]
                     (assoc-in ctx [:request :headers "Authorization"]
                               "Bearer A"))})
        (rf/reg-event :load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (-> db
                       (update :replies (fnil conj []) reply))}
              {:fx [[:rf.http/managed
                     {:reply-to [:load msg] :request {:url (str "http://127.0.0.1:" port "/x")}
                      :decode  :json}]]})))
        ;; First dispatch — interceptor fires.
        (rf/dispatch-sync [:load])
        (await-reply! #(= 1 (count (:replies %))) 5000)
        (is (= "Bearer A" @seen-auth) "first request carried the auth header")
        ;; Clear + dispatch again.
        (reset! seen-auth nil)
        (rf/clear :http-interceptor :auth-header)
        (rf/dispatch-sync [:load])
        (await-reply! #(= 2 (count (:replies %))) 5000)
        (is (nil? @seen-auth)
            "after clear, the second request did NOT carry the auth header")
        (finally (stop-server! srv))))))

;; ---- 5a. single-arity clear FAILS CLOSED under no scope -------------------
;;
;; The fixture pins an ambient `*current-frame* :rf/default`, which would MASK a
;; facade floor (a single-arity clear that recursed
;; `[:rf/default id]`, synthesising the default before delegating). This test
;; clears the ambient scope (`*current-frame* nil`) so NO frame is carried,
;; then invokes the no-opts facade form and asserts it raises the always-on
;; `:rf.error/no-frame-context` rather than silently clearing against a
;; synthesised `:rf/default` chain. Proves the EP-0002 carried invariant is
;; live on the public `(rf/clear :http-interceptor id)` surface — there is no floor.

(deftest clear-http-interceptor-single-arity-fails-closed-under-no-scope
  (testing "no-opts `(rf/clear :http-interceptor id)` under NO
            ambient frame raises :rf.error/no-frame-context (fails closed);
            it does NOT synthesise a :rf/default target. The facade must
            delegate frame resolution to the impl's require-current-frame!,
            never inject :rf/default from absence."
    (binding [rf.frame/*current-frame* nil]
      (let [thrown (try (rf/clear :http-interceptor :some-id)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown)
            "single-arity clear with no carried frame must throw")
        (is (= :rf.error/no-frame-context
               (:rf.error/id (ex-data thrown)))
            "the throw is the always-on :rf.error/no-frame-context — no :rf/default floor")))))

;; ---- 5b. the public {:frame} opts form ------------------------------------
;;
;; `clear-http-interceptor`'s public 2-arity is EXACTLY the trailing
;; `{:frame …}` opts map (mirroring `reg-http-interceptor`'s `:frame`).
;; Two-scalar frame-first is not a public shape; artefact-internal cleanup
;; routes through the `clear-http-interceptor*` seam. Registry-level (no
;; server): assert the opts form targets the named frame from an ambient
;; :rf/default scope without misbinding, and the internal seam clears.

(deftest clear-http-interceptor-frame-arg-spelling-rf2-f28bno
  (testing "`(clear-http-interceptor id {:frame f})` targets frame
            `f` from the ambient :rf/default scope (it binds the frame,
            never silently no-op'ing); the internal
            `clear-http-interceptor*` seam (frame-first) clears too."
    ;; The ambient scope is :rf/default (fixture). Register on BOTH frames —
    ;; the interceptor registry is a per-frame-id atom independent of the
    ;; `frames` registry, so a named frame needs no separate registration (the
    ;; sibling `per-frame-scope` CLJS test registers on `:other` the same way).
    (rf/reg-http-interceptor :fa/on-other   {:frame :fa/other :before (fn [c] c)})
    (rf/reg-http-interceptor :fa/on-default {:before (fn [c] c)})
    (is (= [:fa/on-other]   (mapv :id (rf.http.managed/interceptors-snapshot :fa/other))))
    (is (= [:fa/on-default] (mapv :id (rf.http.managed/interceptors-snapshot :rf/default))))
    ;; (1) PUBLIC opts form clears the NAMED frame from the :rf/default scope —
    ;; the natural `(clear id {:frame f})` guess from the reg shape binds
    ;; :fa/other correctly.
    (rf/clear :http-interceptor :fa/on-other {:frame :fa/other})
    (is (zero? (count (rf.http.managed/interceptors-snapshot :fa/other)))
        "opts {:frame :fa/other} cleared the named frame's slot")
    (is (= [:fa/on-default] (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
        "the :rf/default chain is untouched by the explicit-frame clear")
    ;; (2) INTERNAL frame-first seam clears a named frame's slot.
    (rf/reg-http-interceptor :fa/again {:frame :fa/other :before (fn [c] c)})
    (is (= [:fa/again] (mapv :id (rf.http.managed/interceptors-snapshot :fa/other))))
    (rf.http.middleware/clear-http-interceptor* :fa/other :fa/again)   ;; private seam: (frame id)
    (is (zero? (count (rf.http.managed/interceptors-snapshot :fa/other)))
        "internal frame-first seam (clear-http-interceptor*) cleared the slot")))

;; ---- 5c. the public opts form is EXACT + FAIL-CLOSED ----------------------
;;
;; The opts map is ONLY `{:frame target}`.  A malformed one (empty, nil
;; :frame, misspelled/unknown key, extra key), a non-map second arg, and a
;; two-scalar frame-first spelling all fail closed BEFORE any ambient
;; state is touched — an `(or (:frame opts) ambient-frame)` resolution
;; would silently mis-clear the ambient frame on any of these.
;;
;; That rule has TWO doors, and this test pins both, because
;; each is reachable on its own and they raise DIFFERENT typed errors by
;; design.  The public front door `(rf/clear :http-interceptor id opts)`
;; validates in `clear` itself and raises
;; `:rf.error/registrar-clear-bad-request` — one error id for the one verb,
;; covering an unknown kind and opts on a non-frame-scoped kind as well.  The
;; artefact-level `re-frame.http.middleware/clear-http-interceptor`, which
;; is the `:http/clear-http-interceptor` hook target that `rf/clear`
;; dispatches to, raises its own
;; `:rf.error/http-bad-interceptor` for its own arg validation.  Both
;; share ONE validator (`re-frame.frame/frame-opts?`), which keeps
;; the identically-shaped flows door from carrying a tolerant destructure.

(deftest clear-http-interceptor-opts-form-fail-closed-rf2-s32bf
  (testing "the opts map must be EXACTLY {:frame target}; malformed
            opts, a non-map second arg, and the two-scalar frame-first
            shape fail closed at BOTH doors and leave the ambient interceptor
            untouched — :rf.error/registrar-clear-bad-request through the public
            (rf/clear :http-interceptor id opts), and
            :rf.error/http-bad-interceptor through the artefact-level fn the
            :http/clear-http-interceptor hook reaches. The exact
            {:frame target} form clears."
    (letfn [(threw-with? [error-id thunk]
              (let [ex (try (thunk) nil
                            (catch clojure.lang.ExceptionInfo e e))]
                (and (some? ex)
                     (= error-id (:rf.error/id (ex-data ex))))))
            ;; the public front door: `clear`'s own validator fires first
            (threw-bad? [thunk]
              (threw-with? :rf.error/registrar-clear-bad-request thunk))
            ;; the artefact-level fn the late-bind hook reaches
            (artefact-threw-bad? [opts]
              (threw-with? :rf.error/http-bad-interceptor
                           #(rf.http.middleware/clear-http-interceptor
                              :s32bf/ambient opts)))]
      ;; ambient scope is :rf/default (fixture) — seed a slot there.
      (rf/reg-http-interceptor :s32bf/ambient {:before (fn [c] c)})
      (is (= [:s32bf/ambient]
             (mapv :id (rf.http.managed/interceptors-snapshot :rf/default))))
      ;; every malformed 2-arg form fails closed at BOTH doors; the SECOND
      ;; door — the artefact-level fn the `:http/clear-http-interceptor` hook
      ;; reaches — keeps its own typed error over the same shared validator.
      (doseq [[label opts]
              [["empty opts map (no :frame)"                           {}]
               ["nil :frame"                                           {:frame nil}]
               ["misspelled opts key"                                  {:fram :rf/default}]
               ["extra opts key (map must be exactly {:frame target})" {:frame :rf/default :extra 1}]
               ["non-map second arg"                                   "not-a-map"]
               ["two-scalar frame-first is not a public shape"         :some-frame]]]
        (testing label
          (is (threw-bad? #(rf/clear :http-interceptor :s32bf/ambient opts))
              "public door fails closed with :rf.error/registrar-clear-bad-request")
          (is (artefact-threw-bad? opts)
              "artefact door fails closed with :rf.error/http-bad-interceptor")))
      (is (threw-bad? #(rf/clear :http-interceptor :s32bf/ambient 42))
          "public door: a non-map scalar second arg fails closed")
      ;; NO rejected call touched the ambient chain
      (is (= [:s32bf/ambient]
             (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
          "no malformed clear touched the ambient :rf/default interceptor")
      ;; the exact opts form clears the ambient frame
      (rf/clear :http-interceptor :s32bf/ambient {:frame :rf/default})
      (is (zero? (count (rf.http.managed/interceptors-snapshot :rf/default)))
          "the exact {:frame target} form clears the named frame"))))

;; ---- 6. re-registering an id replaces in place ---------------------------

(deftest re-registering-id-replaces-slot
  (testing "re-registering an id swaps the :before in the existing position; no duplicates"
    (let [order (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (rf/reg-http-interceptor :a {:before (fn [ctx] (swap! order conj :a-v1) ctx)})
        (rf/reg-http-interceptor :b {:before (fn [ctx] (swap! order conj :b)    ctx)})
        ;; Replace :a — should keep its position (first), not append.
        (rf/reg-http-interceptor :a {:before (fn [ctx] (swap! order conj :a-v2) ctx)})
        (rf/reg-event :load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:load msg]
                      :request  {:url (str "http://127.0.0.1:" port "/x")}
                      :decode   :json}]]})))
        (rf/dispatch-sync [:load])
        ;; Await the REPLY, not the `:before` count (§TEST ISOLATION above):
        ;; both `:before` fns fire synchronously inside `dispatch-sync`, so an
        ;; `@order`-count poll is satisfied while the round trip is still in
        ;; flight and this test tears down over its own live response. The
        ;; reply landing implies the whole chain ran.
        (await-reply! #(some? (:reply %)) 5000)
        (is (= [:a-v2 :b] @order)
            "replaced :a's :before fired in :a's original position; no duplicate")
        (finally (stop-server! srv))))))

;; ---- 6b. clear-then-reg lands at the end of the chain --------------------
;;
;; Re-registering an id replaces in place
;; (test 6 above), but `clear-http-interceptor` followed by a fresh
;; `reg-http-interceptor` of the same id has different semantics — the
;; slot was *removed* by clear, so re-registering appends to the end.
;; Per Spec 014 §Chain order,
;; clear-then-reg lands at the end (the slot's prior index is forgotten
;; on clear). Test pins that contract; preserving position across clear
;; would break the documented behaviour
;; and surprise hot-reload tools that DO want a fresh end-of-chain slot.

(deftest clear-then-reg-appends-to-end-of-chain
  (testing "clear-http-interceptor followed by re-reg of the same
            id appends to the end of the chain (the prior position is
            forgotten on clear). Spec 014 §Chain order and frame scope."
    (let [order (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{}")))]
      (try
        ;; Register three interceptors in order: :a, :b, :c.
        (rf/reg-http-interceptor :a {:before (fn [ctx] (swap! order conj :a) ctx)})
        (rf/reg-http-interceptor :b {:before (fn [ctx] (swap! order conj :b) ctx)})
        (rf/reg-http-interceptor :c {:before (fn [ctx] (swap! order conj :c) ctx)})

        ;; Clear :a — slot is removed entirely.
        (rf/clear :http-interceptor :a)
        ;; Re-register :a. Per the contract this is a FRESH registration,
        ;; not a position-preserving replace — it appends to the end.
        (rf/reg-http-interceptor :a {:before (fn [ctx] (swap! order conj :a-fresh) ctx)})

        ;; Confirm the chain order in the registry directly so the test
        ;; pins the slot ordering before the dispatch ever runs.
        (let [chain (rf.http.managed/interceptors-snapshot :rf/default)]
          (is (= [:b :c :a] (mapv :id chain))
              "after clear-then-reg, :a moved to the end (was first; now last)"))

        (rf/reg-event :kg5nw/load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:kg5nw/load msg]
                      :request  {:url (str "http://127.0.0.1:" port "/x")}
                      :decode   :json}]]})))
        (rf/dispatch-sync [:kg5nw/load])
        ;; Await the REPLY, not the `:before` count (§TEST ISOLATION above):
        ;; all three `:before` fns fire synchronously inside `dispatch-sync`,
        ;; so an `@order`-count poll is satisfied while the round trip is still
        ;; in flight and this test tears down over its own live response. The
        ;; reply landing implies the whole chain ran.
        (await-reply! #(some? (:reply %)) 5000)
        (is (= [:b :c :a-fresh] @order)
            "interceptors fired in the post-clear-then-reg order: :b, :c, :a (re-registered)")
        (finally (stop-server! srv))))))

;; ---- 7. invalid interceptor shape raises ---------------------------------

(deftest invalid-interceptor-shape-raises
  (testing "reg-http-interceptor rejects non-keyword
            id, non-map interceptor-map, non-fn :before / :after, missing
            both :before and :after, or non-keyword :frame"
    (doseq [[label id interceptor-map]
            [["non-keyword id"           "string-id" {:before identity}]
             ["non-map interceptor-map"  :x          "not-a-map"]
             ["non-fn :before"           :x          {:before "not-a-fn"}]
             ["non-fn :after"            :x          {:after "not-a-fn"}]
             ["neither :before nor :after — a no-op interceptor"
              :x {:doc "no fns at all"}]
             ["non-keyword :frame"       :x          {:frame "not-a-keyword" :before identity}]]]
      (testing label
        (let [thrown (try (rf/reg-http-interceptor id interceptor-map)
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown))
          (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))))))

;; ---- 8. clear-all-http-interceptors! bulk-clear ---------------------------
;;
;; The test above (`clear-http-interceptor-unregisters`) covers the single-id
;; `clear-http-interceptor`; this one covers the bulk-clear
;; helper `clear-all-http-interceptors!`. Test fixtures and the
;; reset-runtime path use the bulk form to drop every registered chain;
;; a regression that left even one slot populated would only surface as
;; cross-test pollution. Its post-clear request re-runs a registered event
;; through the `:rf.http/managed` fx, so a bulk-clear that reached any other
;; registry goes red here as well.

(deftest clear-all-http-interceptors-empties-every-frame-chain
  (testing "clear-all-http-interceptors! drops every registered :before
            across every frame; subsequent requests fire NO interceptors"
    (let [seen-headers (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (swap! seen-headers conj
                     {:a (header-of ex "X-A")
                      :b (header-of ex "X-B")
                      :c (header-of ex "X-C")})
              (write-response! ex 200 "application/json" "{}")))]
      (try
        ;; Register three interceptors on :rf/default.
        (rf/reg-http-interceptor :hdr-a
          {:before (fn [ctx] (assoc-in ctx [:request :headers "X-A"] "1"))})
        (rf/reg-http-interceptor :hdr-b
          {:before (fn [ctx] (assoc-in ctx [:request :headers "X-B"] "2"))})
        (rf/reg-http-interceptor :hdr-c
          {:before (fn [ctx] (assoc-in ctx [:request :headers "X-C"] "3"))})
        ;; And one on a second frame, so the bulk-clear has more than the
        ;; ambient frame's chain to empty.
        (rf/reg-http-interceptor :hdr-other
          {:frame :test.lfvi/other :before (fn [ctx] ctx)})

        ;; Confirm both frames' chains before the bulk-clear.
        (let [chain (rf.http.managed/interceptors-snapshot :rf/default)]
          (is (= 3 (count chain)))
          (is (= #{:hdr-a :hdr-b :hdr-c}
                 (set (map :id chain)))
              "all three ids appear in the :rf/default chain"))
        (is (= [:hdr-other]
               (mapv :id (rf.http.managed/interceptors-snapshot :test.lfvi/other)))
            "the second frame holds its own chain")

        (rf/reg-event :test.lfvi/load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (-> db (update :replies (fnil conj []) reply))}
              {:fx [[:rf.http/managed
                     {:reply-to [:test.lfvi/load msg] :request {:url (str "http://127.0.0.1:" port "/x")}
                      :decode  :json}]]})))

        ;; First dispatch — all three interceptors fire.
        (rf/dispatch-sync [:test.lfvi/load])
        (await-reply! #(= 1 (count (:replies %))) 5000)
        (let [first-req (first @seen-headers)]
          (is (= "1" (:a first-req)) "X-A header from :hdr-a interceptor")
          (is (= "2" (:b first-req)) "X-B header from :hdr-b interceptor")
          (is (= "3" (:c first-req)) "X-C header from :hdr-c interceptor"))

        ;; Bulk clear.
        (reset! seen-headers [])
        (rf.http.managed/clear-all-http-interceptors!)

        ;; Atom is now empty.
        (is (= {} (rf.http.managed/interceptors-snapshot))
            "the per-frame chain atom is now empty across every frame")

        ;; Second dispatch — none of the cleared interceptors fire.
        (rf/dispatch-sync [:test.lfvi/load])
        (await-reply! #(= 2 (count (:replies %))) 5000)
        (let [second-req (first @seen-headers)]
          (is (nil? (:a second-req)) "X-A is absent after bulk-clear")
          (is (nil? (:b second-req)) "X-B is absent after bulk-clear")
          (is (nil? (:c second-req)) "X-C is absent after bulk-clear"))

        (finally (stop-server! srv))))))

;; ---- sensitivity recomputed from the POST-:before request -----------------
;;
;; A `:before` that MARKS the request sensitive (sets [:request :sensitive?]
;; true) followed by a LATER :before that throws must produce a
;; :rf.error/http-interceptor-failed trace redacted under the EFFECTIVE
;; (post-mark) sensitivity — not the stale pre-chain flag. We assert against
;; a NON-denylisted query param (`customer_email`) so the only thing that can
;; redact it is the per-request :sensitive? flag the first :before set; the
;; query-param denylist alone would leave it verbatim.

(deftest before-marked-sensitive-then-throw-redacts-under-effective-sensitivity
  (testing "a :before sets [:request :sensitive?] true, a later
            :before throws; the interceptor-failed trace redacts the
            NON-denylisted query value because effective sensitivity is
            recomputed from the post-mark request (not the stale pre-chain
            flag)"
    (let [traces      (atom [])
          listener-id (gensym "rznrz-mark-sensitive-")]
      (try
        (rf.trace.tooling/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        ;; First :before marks the request sensitive.
        (rf/reg-http-interceptor :mark-sensitive
          {:before (fn [ctx] (assoc-in ctx [:request :sensitive?] true))})
        ;; Second :before throws — fires AFTER the mark.
        (rf/reg-http-interceptor :boom
          {:before (fn [_ctx] (throw (ex-info "kaboom" {})))})
        (rf/reg-event :rznrz/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   ;; customer_email is NOT in the query-param denylist, so
                   ;; it only redacts when the request is effectively sensitive.
                   {:request {:url "https://api.example.invalid/v1?customer_email=alice%40example.com&page=2"}
                    :decode     :json
                    :on-success nil
                    :on-failure nil}]]}))
        (rf/dispatch-sync [:rznrz/load])
        (rf.test-support/poll-until
          #(some (fn [t] (= :rf.error/http-interceptor-failed (:operation t))) @traces)
          {:label ":rf.error/http-interceptor-failed surfaced (sensitivity recompute)"})
        (let [w    (first (filter #(= :rf.error/http-interceptor-failed (:operation %)) @traces))
              tags (:tags w)]
          (is (some? w))
          ;; A sensitive request redacts ALL query values (broader than the
          ;; denylist), so BOTH the non-denylisted customer_email AND page
          ;; scrub. The load-bearing signal is that customer_email — which the
          ;; denylist alone would leave verbatim — is redacted: that can only
          ;; happen if the trace saw the post-:before-mark (sensitive) request.
          (is (= "https://api.example.invalid/v1?customer_email=:rf/redacted&page=:rf/redacted"
                 (:url tags))
              "the NON-denylisted query value is redacted — proving the trace
               saw the post-:before-mark (sensitive) request, not the stale
               pre-chain non-sensitive flag (a sensitive request scrubs ALL params)")
          (is (true? (:sensitive? w))
              ":sensitive? stamped because the effective request is sensitive"))
        (finally
          (rf.trace.tooling/unregister-listener! listener-id))))))

;; ---- the :before ctx carries the request's resolved :sensitive? -----------

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

;; ---- CLJS-only-key check runs on the POST-:before request -----------------
;;
;; A :before that ADDS a JVM-degraded CLJS-only key (:credentials / :mode /
;; …) into the request must trip the :rf.http/cljs-only-key-ignored-on-jvm
;; warning. A check-cljs-only-keys! run on the ORIGINAL args before
;; the chain would let a :before-added key proceed on JVM with no degraded-key
;; warning at all.

(deftest before-added-cljs-only-key-trips-degradation-warning
  (testing "a :before that adds a CLJS-only key (:credentials)
            into the request trips :rf.http/cljs-only-key-ignored-on-jvm; the
            check runs against the post-:before request, not the original
            args"
    (let [traces      (atom [])
          listener-id (gensym "rznrz-cljs-only-")
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf.trace.tooling/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        ;; The request as DISPATCHED carries no CLJS-only key; the :before
        ;; adds :credentials into the request map.
        (rf/reg-http-interceptor :add-credentials
          {:before (fn [ctx] (assoc-in ctx [:request :credentials] :include))})
        (rf/reg-event :rznrz.cljsonly/load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:rznrz.cljsonly/load msg] :request {:url (str "http://127.0.0.1:" port "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:rznrz.cljsonly/load])
        (await-reply! #(some? (:reply %)) 5000)
        (let [warns (filter #(= :rf.http/cljs-only-key-ignored-on-jvm (:operation %))
                            @traces)
              keys-seen (set (map #(get-in % [:tags :key]) warns))]
          (is (contains? keys-seen :credentials)
              "the :before-added :credentials key trips the JVM degradation
               warning — proving check-cljs-only-keys! ran on the post-chain
               request, not the original (key-free) args"))
        (finally
          (rf.trace.tooling/unregister-listener! listener-id)
          (stop-server! srv))))))

;; ===========================================================================
;; `:after` response-side hook
;; ===========================================================================
;;
;; Per Spec 014 §Middleware: each HTTP interceptor may carry an optional `:after`
;; fn `(fn [ctx response] response')`. The `:after` chain runs in REVERSE
;; registration order after the response is built and BEFORE
;; `:on-success` / `:on-failure` fire. `:after` sees the SAME ctx the
;; `:before` produced for this request — request-correlated handling
;; (response-time telemetry, header parsing, auth refresh).

(defn- single-success-server
  "Helper: starts an HTTP server that always 200s with the given JSON
  `body-string` (content-type `application/json`). Pair with
  `:decode :json` on the request."
  [body-string]
  (start-server!
    (fn [^HttpExchange ex]
      (write-response! ex 200 "application/json" body-string))))

(defn- await-reply-with-payload!
  "Wait until `:reply` lands in app-db, then return the recorded value."
  []
  (await-reply! #(some? (:reply %)) 5000)
  (:reply (rf/app-db-value :rf/default)))

;; ---- 1. :after runs in REVERSE registration order ------------------------

(deftest after-runs-in-reverse-registration-order
  (testing "three :after interceptors registered :a → :b → :c
            fire in the reverse order :c → :b → :a on the response side
            (mirror of the event-interceptor onion, Spec 002)."
    (let [order (atom [])
          srv   (single-success-server "{\"ok\":1}")]
      (try
        (rf/reg-http-interceptor :a
          {:after (fn [_ctx resp] (swap! order conj :a) resp)})
        (rf/reg-http-interceptor :b
          {:after (fn [_ctx resp] (swap! order conj :b) resp)})
        (rf/reg-http-interceptor :c
          {:after (fn [_ctx resp] (swap! order conj :c) resp)})
        (rf/reg-event :uheqq/load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:uheqq/load msg] :request {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load])
        (await-reply-with-payload!)
        (is (= [:c :b :a] @order)
            ":after fires in reverse registration order")
        (finally (stop-server! srv))))))

;; ---- 2. response transform threads through -------------------------------

(deftest after-can-transform-the-response-shape
  (testing "`:after` returns the (possibly-transformed)
            response; the transformed shape is what reaches the
            `:on-success` event vector via `build-reply-event`."
    (let [srv (single-success-server "{\"original\":\"payload\"}")]
      (try
        (rf/reg-http-interceptor :transformer
          {:after (fn [_ctx resp]
                    ;; tag the value with a marker so we can confirm
                    ;; the :on-success target saw the modified shape.
                    (update resp :value
                            (fn [v] (assoc v :touched-by :after))))})
        (rf/reg-event :uheqq/load-xform
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:uheqq/load-xform msg] :request {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-xform])
        (let [reply (await-reply-with-payload!)]
          (is (= :ok (:status reply))
              "the reply still classifies as :status :ok")
          (is (= :after (get-in reply [:value :touched-by]))
              ":after's mutation of (:value reply) reaches the on-success target")
          (is (= "payload" (get-in reply [:value :original]))
              "the underlying response value is preserved"))
        (finally (stop-server! srv))))))

;; ---- 3. interceptors without :after are transparent in the response chain

(deftest after-less-interceptors-are-transparent
  (testing "interceptors registered with only `:before` (no
            `:after`) MUST be skipped on the response side, not nil-
            substituted. A `:before`-only :a followed by an `:after`-only
            :b means the response chain visits only :b — :a is invisible."
    (let [order (atom [])
          srv   (single-success-server "{\"ok\":\"transparent\"}")]
      (try
        (rf/reg-http-interceptor :before-only
          {:before (fn [ctx]
                     (swap! order conj :before-only-fired)
                     ctx)})
        (rf/reg-http-interceptor :after-only
          {:after (fn [_ctx resp]
                    (swap! order conj :after-only-fired)
                    resp)})
        (rf/reg-event :uheqq/load-transparent
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:uheqq/load-transparent msg] :request {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-transparent])
        (await-reply-with-payload!)
        (is (= [:before-only-fired :after-only-fired] @order)
            ":before-only fired on request side; :after-only fired on response side; no nil-substitution surfaced")
        (finally (stop-server! srv))))))

;; ===========================================================================
;; four motivating use cases
;; ===========================================================================

;; ---- USE-CASE 1. Rate-limit header parsing -------------------------------

(deftest motivating-rate-limit-header-parse
  (testing "use case 1 — an `:after` interceptor parses
            `X-RateLimit-Remaining` from the response and tags the reply
            with a structured `:rate-limit` slot so a downstream
            `:on-success` handler can throttle subsequent requests
            without re-parsing the same header in every handler."
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (-> ex .getResponseHeaders
                      (.set "X-RateLimit-Remaining" "37"))
                  (-> ex .getResponseHeaders
                      (.set "X-RateLimit-Limit" "100"))
                  (write-response! ex 200 "application/json"
                                   "{\"items\":[1,2,3]}")))]
      (try
        (rf/reg-http-interceptor :rate-limit-parse
          {:before (fn [ctx]
                     ;; record the response headers slot into ctx via the
                     ;; chain — `:before` simply marks the request so we
                     ;; can correlate; the real read happens in :after.
                     (assoc ctx ::rate-limit-aware true))
           :after  (fn [ctx resp]
                     ;; The successful reply carries the
                     ;; response wire facts under `:meta` (`:status` /
                     ;; `:status-text` / normalized `:headers`), so the
                     ;; `:after` parses the headers the server ACTUALLY
                     ;; emitted (lower-cased names — the transport's
                     ;; cross-host normalized shape) into a structured
                     ;; `:rate-limit` slot. Downstream `:on-success`
                     ;; handlers consume the structured form without
                     ;; re-parsing per-call.
                     (let [hs (get-in resp [:meta :headers])]
                       (assoc resp :rate-limit
                              {:remaining (some-> (get hs "x-ratelimit-remaining")
                                                  Long/parseLong)
                               :limit     (some-> (get hs "x-ratelimit-limit")
                                                  Long/parseLong)
                               :ctx-aware (::rate-limit-aware ctx)})))})
        (rf/reg-event :uheqq/load-rate
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:uheqq/load-rate msg] :request {:url (str "http://127.0.0.1:" (:port srv) "/items")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-rate])
        (let [reply (await-reply-with-payload!)]
          (is (= 37  (get-in reply [:rate-limit :remaining]))
              "X-RateLimit-Remaining parsed FROM the server-emitted header riding [:meta :headers]")
          (is (= 100 (get-in reply [:rate-limit :limit]))
              "X-RateLimit-Limit parsed from the actual response header")
          (is (true? (get-in reply [:rate-limit :ctx-aware]))
              ":after read the :before's stashed ctx flag (request-correlation works)")
          (is (= 200 (get-in reply [:meta :status]))
              "the actual response status rides [:meta :status] on the delivered reply"))
        (finally (stop-server! srv))))))

;; ---- USE-CASE 2. Response-time telemetry --------------------------------

(deftest motivating-response-time-telemetry
  (testing "use case 2 — :before stamps a wall-clock start; the
            :after reads it back via the SHARED ctx and computes the
            response time delta. This is exactly what the
            ctx-carried-from-before contract unlocks."
    (let [observed (atom nil)
          srv      (single-success-server "{\"items\":3}")]
      (try
        (rf/reg-http-interceptor :response-time
          {:before (fn [ctx]
                     (assoc ctx ::started-at (System/currentTimeMillis)))
           :after  (fn [ctx resp]
                     (let [started (::started-at ctx)
                           ended   (System/currentTimeMillis)
                           delta   (- ended started)]
                       (reset! observed {:delta-ms delta :start started})
                       (assoc resp :telemetry {:elapsed-ms delta})))})
        (rf/reg-event :uheqq/load-telemetry
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:uheqq/load-telemetry msg] :request {:url (str "http://127.0.0.1:" (:port srv) "/telemetry")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-telemetry])
        (let [reply (await-reply-with-payload!)]
          (is (some? (:start @observed))
              ":before's start mark was visible to :after via ctx")
          (is (>= (:delta-ms @observed) 0)
              "wall-clock delta is non-negative (computed from ctx-carried mark)")
          (is (>= (get-in reply [:telemetry :elapsed-ms]) 0)
              ":after's transformation reached the on-success reply"))
        (finally (stop-server! srv))))))

;; ---- USE-CASE 3. Cache-Control inspection -------------------------------

(deftest motivating-cache-control-inspection
  (testing "use case 3 — an :after interceptor parses
            the server-emitted Cache-Control header off the reply's
            [:meta :headers] and tags the reply with a structured :cache
            slot. Downstream `:on-success` handlers consume the structured
            form without re-parsing the header string at every dispatch
            site."
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (-> ex .getResponseHeaders
                      (.set "Cache-Control" "max-age=600, public"))
                  (write-response! ex 200 "application/json"
                                   "{\"doc\":\"hello\"}")))]
      (try
        (rf/reg-http-interceptor :cache-control
          {:after (fn [_ctx resp]
                    ;; Parse the Cache-Control header the
                    ;; server ACTUALLY emitted off the reply's
                    ;; [:meta :headers] into a structured :cache slot.
                    ;; These assertions fail if the emitted header changes
                    ;; without changing the expected parse.
                    (let [cc (get-in resp [:meta :headers "cache-control"])]
                      (assoc resp :cache
                             {:max-age (some->> cc
                                                (re-find #"max-age=(\d+)")
                                                second
                                                Long/parseLong)
                              :public? (boolean (when cc (re-find #"\bpublic\b" cc)))})))})
        (rf/reg-event :uheqq/load-cache
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:uheqq/load-cache msg] :request {:url (str "http://127.0.0.1:" (:port srv) "/cache")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-cache])
        (let [reply (await-reply-with-payload!)]
          (is (= 600 (get-in reply [:cache :max-age]))
              "Cache-Control max-age parsed FROM the server-emitted header riding [:meta :headers]")
          (is (true? (get-in reply [:cache :public?]))
              "Cache-Control 'public' directive parsed from the actual header value"))
        (finally (stop-server! srv))))))

;; ---- USE-CASE 4. 401 auth-token refresh ---------------------------------

(deftest motivating-401-auth-refresh
  (testing "use case 4 — an :after interceptor inspects the
            failure shape; on `:rf.http/http-4xx` with `:status 401` it
            tags the reply with `:auth-refresh-required true` so a
            downstream handler can mint a refresh-token dispatch
            without every call site reimplementing the 401 check."
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 401 "application/json"
                                   "{\"error\":\"expired\"}")))]
      (try
        (rf/reg-http-interceptor :auth-refresh
          {:after (fn [_ctx resp]
                    (if (and (= :error (:status resp))
                             (= :rf.http/http-4xx
                                (get-in resp [:error :kind]))
                             (= 401 (get-in resp [:error :status])))
                      (assoc resp :auth-refresh-required true)
                      resp))})
        (rf/reg-event :uheqq/load-401
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:uheqq/load-401 msg] :request {:url (str "http://127.0.0.1:" (:port srv) "/protected")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-401])
        (let [reply (await-reply-with-payload!)]
          (is (= :error (:status reply))
              "the 401 classifies as a :status :error reply")
          (is (= :rf.http/http-4xx (get-in reply [:error :kind]))
              "failure category is :rf.http/http-4xx")
          (is (= 401 (get-in reply [:error :status]))
              "status 401 rides on the failure map under :error")
          (is (true? (:auth-refresh-required reply))
              ":after attached :auth-refresh-required so a downstream handler can mint the refresh dispatch"))
        (finally (stop-server! srv))))))

;; ---- chain resolution is at ISSUE time ------------------------------------
;;
;; The two tests below are the LIVE-TRANSPORT arm of the issue-time chain contract:
;; a managed request captures its frame's interceptor chain immediately
;; before running `:before`, and its response walks that same captured
;; vector — through the real handler, the real transport, and the retry
;; handoff. The host-symmetric (JVM + CLJS) arm, which pins the capture
;; POINT and the empty-capture case on the canned seam, is
;; `re-frame.http-interceptor-chain-capture-cljs-test`.
;;
;; Neither test depends on elapsed time. The first holds its response open
;; on a latch the test itself releases; the second orders its registry
;; mutation with the server's own attempt counter, so the mutation
;; provably lands between attempt 1 and attempt 2.

(deftest live-response-walks-its-issue-time-chain-rf2-v3f6
  (testing "a response held open across a registry change walks
            the chain its REQUEST was issued under: the cleared :after still
            runs (holding the ctx its own :before stamped) and the newly
            registered one does not run at all"
    (let [arrived (java.util.concurrent.CountDownLatch. 1)
          release (java.util.concurrent.CountDownLatch. 1)
          log     (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (.countDown arrived)
              ;; hold the response open until the test releases it — the
              ;; request is genuinely outstanding, with no sleep anywhere
              (.await release 10 java.util.concurrent.TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-http-interceptor :issue-time
          {:before (fn [ctx] (assoc ctx ::mark :stamped))
           :after  (fn [ctx resp]
                     (swap! log conj [:issue-time (::mark ctx)])
                     resp)})
        (rf/reg-event :v3f6/load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:v3f6/load msg]
                      :request  {:url (str "http://127.0.0.1:" port "/held")}
                      :decode   :json}]]})))
        (rf/dispatch-sync [:v3f6/load])
        (is (.await arrived 10 java.util.concurrent.TimeUnit/SECONDS)
            "control: the request reached the server, so its :before walk —
             and therefore its chain capture — has already happened")

        ;; The registry changes while the response is still on the wire.
        (rf/clear :http-interceptor :issue-time)
        (rf/reg-http-interceptor :registered-mid-flight
          {:after (fn [ctx resp]
                    (swap! log conj [:registered-mid-flight (::mark ctx)])
                    resp)})
        (is (= [:registered-mid-flight]
               (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
            "control: the LIVE registry really did change while the response
             was outstanding")

        (.countDown release)
        (await-reply! #(some? (:reply %)) 10000)
        (is (= [[:issue-time :stamped]] @log)
            "the response walked its ISSUE-TIME chain: :issue-time's :after ran
             (holding the ctx its own :before stamped) even though the slot had
             been cleared, and :registered-mid-flight never joined a request it
             was not registered for")
        (finally
          (.countDown release)
          (stop-server! srv))))))

(deftest retry-attempts-keep-the-issue-time-chain-rf2-v3f6
  (testing "the captured chain survives the retry handoff: a
            registry change made BETWEEN attempt 1 and attempt 2 does not
            reach the reply, and `:before` is not re-run per attempt"
    (let [hits    (atom 0)
          befores (atom 0)
          log     (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (let [n (swap! hits inc)]
                (when (= 1 n)
                  ;; Ordered by the server, not by a clock: this runs while
                  ;; attempt 1 is being answered, so it is strictly before
                  ;; the retry is issued. Registration happens on the server
                  ;; thread, which carries no ambient frame — name the frame.
                  (rf/reg-http-interceptor :registered-mid-retry
                    {:frame  :rf/default
                     :after  (fn [ctx resp]
                               (swap! log conj [:registered-mid-retry (::mark ctx)])
                               resp)})
                  (rf/clear :http-interceptor :issue-time {:frame :rf/default}))
                (if (= 1 n)
                  (write-response! ex 500 "application/json" "{\"err\":true}")
                  (write-response! ex 200 "application/json" "{\"ok\":true}")))))]
      (try
        (rf/reg-http-interceptor :issue-time
          {:before (fn [ctx] (swap! befores inc) (assoc ctx ::mark :stamped))
           :after  (fn [ctx resp]
                     (swap! log conj [:issue-time (::mark ctx)])
                     resp)})
        (rf/reg-event :v3f6/retry-load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:v3f6/retry-load msg]
                      :request  {:url (str "http://127.0.0.1:" port "/retried")}
                      :decode   :json
                      :retry    {:on           #{:rf.http/http-5xx}
                                 :max-attempts 2
                                 :backoff      {:base-ms 5 :factor 1 :max-ms 10}}}]]})))
        (rf/dispatch-sync [:v3f6/retry-load])
        (let [db (await-reply! #(some? (:reply %)) 10000)]
          (is (= :ok (get-in db [:reply :status]))
              "the retry recovered on attempt 2")
          (is (= 2 @hits) "control: the server really saw two attempts")
          (is (= [:registered-mid-retry]
                 (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
              "control: the registry really did change between the attempts")
          (is (= 1 @befores)
              ":before is walked once per REQUEST, not once per attempt")
          (is (= [[:issue-time :stamped]] @log)
              "the retried attempt's reply walked the chain captured at ISSUE —
               the capture rides the ctx across the retry handoff"))
        (finally (stop-server! srv))))))
