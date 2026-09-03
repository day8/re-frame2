(ns re-frame.http-reply-tail-test
  "JVM coverage for the coupled reply-tail correctness fixes:

  - rf2-bvw9ut — the reply-target SHAPE (vector-or-nil) is validated at
    DISPATCH time (`re-frame.http.handlers/validate-reply-target!`), BEFORE
    the request is issued, per Spec 014 §Request envelope. A bare-keyword
    `:on-success` fails fast at the fx-call site rather than issuing the
    request and throwing async in the reply tail.

  - rf2-ln85eg — a REPLY-TAIL exception (a throwing `:after` interceptor, or
    a malformed reply target the dispatch-time guard did not catch) thrown
    AFTER the transport already succeeded must NOT be reclassified as a
    transport rejection. On the JVM the pre-fix throw escaped the unobserved
    `whenComplete` future and vanished silently (the caller hung). Post-fix
    the transport FENCES the reply tail and surfaces the failure once as
    `:rf.error/http-reply-tail-failed` — observably, and without retry.

  The JVM half is load-bearing for the silent-swallow defect (the
  `CompletableFuture.whenComplete` future is JVM-specific); the CLJS
  retry-storm half lives in `re-frame.http-reply-tail-cljs-test`.

  Uses the JDK `com.sun.net.httpserver.HttpServer` harness (same shape as
  `re-frame.http-interceptors-test`) so the number of times the request
  actually reaches the wire is observable — the load-bearing signal that a
  reply-tail throw does NOT re-send an already-completed request."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.handlers :as rf.http.handlers]
            ;; Requiring the managed artefact publishes the `:rf.http/managed`
            ;; fx + the `reg-http-interceptor` late-bind hooks the tests drive.
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.test-support :as rf.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- in-process server harness (hit-counting) ------------------------------

(defn- start-counting-200-server!
  "An HTTP server that always 200s a JSON body and increments `hits`
  (an `AtomicInteger`) on every request that reaches the wire."
  [^AtomicInteger hits]
  (let [handler (fn [^HttpExchange ex]
                  (.incrementAndGet hits)
                  (let [bytes (.getBytes "{\"ok\":true}" "UTF-8")]
                    (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
                    (.sendResponseHeaders ex 200 (long (count bytes)))
                    (with-open [os (.getResponseBody ex)] (.write os bytes))))
        server  (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx     (.createContext server "/")]
    (.setHandler ctx
                 (reify HttpHandler
                   (handle [_ exchange] (handler exchange))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}] (.stop ^HttpServer server 0))

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    (gensym "reply-tail-cap-")]
    (try
      (rf.trace/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (rf.trace/unregister-listener! cb-id)))))

(defn- ops [captured op]
  (filter #(= op (:operation %)) @captured))

;; ===========================================================================
;; rf2-bvw9ut — dispatch-time reply-target SHAPE validation
;; ===========================================================================

(def ^:private validate-reply-target! @#'rf.http.handlers/validate-reply-target!)

(deftest bvw9ut-shape-validated-at-dispatch-time-unit
  (testing "rf2-bvw9ut — validate-reply-target! rejects a non-vector non-nil
            reply target with :rf.error/http-bad-reply-target, and accepts an
            event vector or an explicit nil (fire-and-forget)"
    ;; A bare keyword is malformed for each of the three reply-target keys.
    (doseq [k [:reply-to :on-success :on-failure]]
      (let [thrown (try (validate-reply-target! {k :items/loaded})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown) (str "a bare-keyword " k " must throw"))
        (is (= :rf.error/http-bad-reply-target (:rf.error/id (ex-data thrown)))
            (str k " → :rf.error/http-bad-reply-target"))
        (is (= k (:key (ex-data thrown)))
            "the offending key rides the ex-data")))
    ;; A map / string target is likewise malformed.
    (is (= :rf.error/http-bad-reply-target
           (:rf.error/id (ex-data (try (validate-reply-target! {:on-success {:not :a-vector}})
                                       (catch clojure.lang.ExceptionInfo e e)))))
        "a map reply target is rejected")
    ;; Valid shapes pass untouched.
    (is (nil? (validate-reply-target! {:on-success [:items/loaded]}))
        "an event vector passes")
    (is (nil? (validate-reply-target! {:on-success nil}))
        "an explicit nil (fire-and-forget) passes")
    (is (nil? (validate-reply-target! {:reply-to [:load] :on-failure nil}))
        "a vector :reply-to with an explicit-nil :on-failure passes")))

(deftest bvw9ut-bare-keyword-on-success-rejected-before-network
  (testing "rf2-bvw9ut — dispatching :rf.http/managed with a bare-keyword
            :on-success is REJECTED at dispatch time: the server is never hit
            (the request is not issued), and the throw is the dispatch-time
            :rf.error/http-bad-reply-target — not an async reply-tail throw"
    (let [hits         (AtomicInteger. 0)
          {:keys [port] :as srv} (start-counting-200-server! hits)]
      (try
        (with-trace-capture
          (fn [captured]
            (rf/reg-event :bvw9ut/load
              (fn [_ _]
                {:fx [[:rf.http/managed
                       ;; bare keyword, non-vector non-nil — malformed target
                       {:request    {:url (str "http://127.0.0.1:" port "/x")}
                        :decode     :json
                        :on-success :items/loaded}]]}))
            ;; The throw fires inside the fx dispatch loop; trap it so the
            ;; test can assert on the observable surface (server-not-hit +
            ;; the error trace).
            (try (rf/dispatch-sync [:bvw9ut/load])
                 (catch Throwable _ nil))
            ;; No network call was ever made — the guard ran before run-attempt!.
            (is (zero? (.get hits))
                "the request was rejected at dispatch time — the server saw zero hits")
            ;; The dispatch-time reject surfaces the bad-reply-target id (either
            ;; directly, or nested as the cause of the fx-handler-exception).
            (let [saw-bad-target?
                  (some (fn [ev]
                          (let [tags (:tags ev)]
                            (or (= :rf.error/http-bad-reply-target (:operation ev))
                                (= :rf.error/http-bad-reply-target
                                   (:rf.error/id tags))
                                (= :rf.error/http-bad-reply-target
                                   (:rf.error/id (:exception tags))))))
                        @captured)]
              (is (or saw-bad-target?
                      (seq (ops captured :rf.error/fx-handler-exception)))
                  "a dispatch-time error surfaced (bad-reply-target / fx-handler-exception)"))))
        (finally (stop-server! srv))))))

;; ===========================================================================
;; rf2-ln85eg — reply-tail throw is observed, not swallowed; not retried
;; ===========================================================================

(deftest ln85eg-after-throw-over-2xx-observed-not-swallowed
  (testing "rf2-ln85eg (JVM) — a throwing :after interceptor over a 2xx is
            surfaced observably as :rf.error/http-reply-tail-failed (not
            swallowed into the unobserved whenComplete future), the request is
            hit EXACTLY ONCE (no retry / re-send even under a
            :retry {:on #{:rf.http/transport}} policy), and NO :on-success
            reply is delivered (delivery is what threw)"
    (let [hits         (AtomicInteger. 0)
          {:keys [port] :as srv} (start-counting-200-server! hits)]
      (try
        (with-trace-capture
          (fn [captured]
            (rf/reg-http-interceptor :boom-after
              {:after (fn [_ctx _resp]
                        (throw (ex-info "reply-tail kaboom" {:detail :synthetic})))})
            (rf/reg-event :ln85eg/reply
              (fn [{:keys [db]} [_ payload]] {:db (assoc db :reply payload)}))
            (rf/reg-event :ln85eg/load
              (fn [_ _]
                {:fx [[:rf.http/managed
                       {:request    {:url (str "http://127.0.0.1:" port "/x")}
                        :decode     :json
                        ;; a retryable transport policy — the pre-fix leak
                        ;; misclassified the reply-tail throw as
                        ;; :rf.http/transport and would retry under this.
                        :retry      {:on #{:rf.http/transport} :max-attempts 3}
                        :on-success [:ln85eg/reply]
                        :on-failure [:ln85eg/reply]}]]}))
            (rf/dispatch-sync [:ln85eg/load])
            ;; The observable post-fix signal: the reply-tail failure surfaces
            ;; (rather than vanishing into the whenComplete future). Poll for it
            ;; — a regression that reclassified/retried would never emit it.
            (rf.test-support/poll-until
              #(seq (ops captured :rf.error/http-reply-tail-failed))
              {:timeout-ms 5000 :label ":rf.error/http-reply-tail-failed surfaced"})
            ;; EXACTLY ONE wire hit — no re-send of the already-completed 2xx.
            (is (= 1 (.get hits))
                "the request reached the wire exactly once — no retry-storm / re-send")
            ;; The reply-tail failure trace is observed exactly once and names
            ;; the caught interceptor error + the reply branch that threw.
            (let [rtf (ops captured :rf.error/http-reply-tail-failed)]
              (is (= 1 (count rtf))
                  "exactly one :rf.error/http-reply-tail-failed (observed, not swallowed)")
              (let [tags (:tags (first rtf))]
                (is (= :success (:kind tags))
                    "the success reply branch's delivery is what threw")
                (is (= :rf.error/http-interceptor-failed (:reply-error-id tags))
                    "the caught throw's id (the :after interceptor failure) rides the trace")))
            ;; No reply was delivered — delivery threw.
            (is (nil? (:reply (rf/app-db-value :rf/default)))
                "no :on-success / :on-failure reply landed (the reply tail threw)")
            ;; And crucially it was NOT reclassified as a transport failure.
            (is (empty? (filter (fn [ev]
                                  (= :rf.http/transport
                                     (get-in ev [:tags :kind])))
                                @captured))
                "the reply-tail throw was NOT reclassified as a :rf.http/transport failure")))
        (finally (stop-server! srv))))))
