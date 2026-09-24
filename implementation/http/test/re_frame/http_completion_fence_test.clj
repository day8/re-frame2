(ns re-frame.http-completion-fence-test
  "The platform completion callback is fenced, and the resulting error is
  observable in PRODUCTION.

  ## The fence

  `dispatch-reply!`'s reply-tail fence covers the BOTTOM of the
  completion callback: the `:after` chain and the late-bind reply dispatch.
  The completion fence covers everything ABOVE it — the 4xx/5xx/2xx cascade,
  response-body schema classification, the retry decision, the finalise +
  teardown. Unfenced, a throw there would fail differently and badly on each
  host:

  - JVM — the throw would escape into the `whenComplete` stage nobody holds,
    which completes that discarded future exceptionally and swallows it. No
    reply, no registry clear: the request would hang in flight for ever,
    silently.
  - CLJS — the throw would reject the promise `.then` returns, so the `.catch`
    would reclassify it as `:rf.http/transport` and `maybe-retry!` would
    RE-SEND a request whose 2xx had already landed. That half is pinned in
    `re-frame.http-completion-fence-cljs-test`, because the double-send is only
    reachable on the Fetch path; this namespace carries the JVM half, where the
    load-bearing symptom is the silent hang.

  ## Production observability

  `emit-reply-tail-error!` fans out through BOTH error substrates via the
  `:error-emit/emit-error-both` hook. Behind an outer `interop/debug-enabled?`
  gate it would reach the dev trace ONLY, so in a production CLJS bundle — the
  one place a throwing `:after` cannot be caught by running the tests — the
  reply would vanish with nothing left behind, although Spec 014 §Failure mode
  promises the throw is surfaced \"observably\".

  The last test pins that on the axis that
  actually carries the promise: `register-error-listener!`, the ALWAYS-ON
  registry (surface #4), NOT `trace.tooling/register-listener!`. The dev-trace
  assertion cannot tell the two apart — it is green either way — so a pin
  written against the trace bus would prove nothing about production.

  ## On planting the throw

  A `:decode` schema declaring a per-slot mark while the shared walker hook is
  unbound, throwing `:rf.error/schemas-artefact-missing` out of
  `classify-decoded`, is not reachable HERE, because `handlers/normalise-args`
  forces that check at dispatch time (the request is refused before it is
  issued). So the fence is exercised by redefining `classify-decoded` to throw,
  which lands the throw at `handle-response!`'s
  2xx accept phase, ABOVE `finalise-success!` and therefore above the registry
  clear and above the reply-tail fence.

  Each test asserts that PRECONDITION rather than assuming it — that the
  planted throw was actually reached, and that it landed above the reply tail
  rather than inside the reply-tail fence. A pin that threw below the
  fence would pass while exercising nothing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.http.privacy-body :as rf.http.privacy-body]
            [re-frame.http.registry :as rf.http.registry]
            ;; Requiring the managed artefact publishes the `:rf.http/managed`
            ;; fx + the `reg-http-interceptor` late-bind hooks.
            [re-frame.http.managed]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- in-process server harness (hit-counting) ------------------------------

(defn- start-counting-200-server!
  "An HTTP server that always 200s a JSON body and increments `hits` on every
  request that reaches the wire. The hit count is the load-bearing signal that
  a fenced throw does NOT re-send an already-completed request."
  [^AtomicInteger hits]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex]
            (.incrementAndGet hits)
            (let [bytes (.getBytes "{\"ok\":true}" "UTF-8")]
              (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
              (.sendResponseHeaders ex 200 (long (count bytes)))
              (with-open [os (.getResponseBody ex)] (.write os bytes)))))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [^HttpServer server]}] (.stop server 0))

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    (gensym "completion-fence-cap-")]
    (try
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (rf.trace.tooling/unregister-listener! cb-id)))))

(defn- ops [captured op]
  (filter #(= op (:operation %)) @captured))

(defn- settled?
  "Truthy once `pred` holds; false rather than throwing on timeout, so the
  caller can turn a timeout into a named assertion failure instead of an
  error with no verdict attached."
  [pred label]
  (try
    (rf.test-support/poll-until pred {:timeout-ms 5000 :interval-ms 10 :label label})
    (catch Exception _ false)))

;; ===========================================================================
;; The fence (JVM) — a throw above the reply tail is fenced, not swallowed
;; ===========================================================================

(deftest completion-throw-is-fenced-and-torn-down-not-swallowed
  (testing "(JVM) a throw in the completion cascade ABOVE the reply
            tail is caught at the completion boundary, surfaced once as
            :rf.error/http-reply-tail-failed, and the in-flight registry is
            cleared; unfenced it would vanish into the unobserved whenComplete
            future, leaving the request in flight for ever with nothing on any
            surface"
    (let [hits  (AtomicInteger. 0)
          calls (atom 0)
          srv   (start-counting-200-server! hits)]
      (try
        (with-trace-capture
          (fn [captured]
            (with-redefs [rf.http.privacy-body/classify-decoded
                          (fn [& _]
                            (swap! calls inc)
                            (throw (ex-info "completion cascade kaboom"
                                            {:rf.error/id :rf.error/schemas-artefact-missing})))]
              (rf/reg-event :fence/reply
                (fn [{:keys [db]} [_ payload]] {:db (assoc db :reply payload)}))
              (rf/reg-event :fence/load
                (fn [_ _]
                  {:fx [[:rf.http/managed
                         {:request    {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                          :decode     :json
                          ;; A retryable transport policy. If the throw were
                          ;; (mis)classified as a transport rejection this is
                          ;; what would re-send the completed 2xx.
                          :retry      {:on #{:rf.http/transport} :max-attempts 3}
                          :request-id :fence/req
                          :reply-to   [:fence/reply]}]]}))
              (rf/dispatch-sync [:fence/load])

              (let [surfaced? (settled?
                                #(seq (ops captured :rf.error/http-reply-tail-failed))
                                ":rf.error/http-reply-tail-failed surfaced")]

                ;; ---- PRECONDITIONS ------------------------------------
                ;; (1) The planted throw was actually REACHED. If the cascade
                ;; stops calling `classify-decoded` on the 2xx path this reads
                ;; 0 and the pin reds rather than passing while exercising
                ;; nothing.
                (is (pos? @calls)
                    "PRECONDITION: the planted throw was reached in the completion cascade")
                ;; (2) It landed ABOVE the reply tail. `:rf.http/replied` is
                ;; emitted from `dispatch-success!`, below `finalise-success!`;
                ;; its absence is what says this is the COMPLETION fence and
                ;; not `dispatch-reply!`'s reply-tail one. If the throw ever
                ;; drifts below, this reds.
                (is (empty? (ops captured :rf.http/replied))
                    "PRECONDITION: the throw landed ABOVE the reply tail — no reply envelope was built")

                ;; ---- VERDICT ------------------------------------------
                (is surfaced?
                    "the completion throw was OBSERVED, not swallowed into the
                     unheld whenComplete future (a timeout here is the unfenced
                     silent hang)")
                (is (= 1 (count (ops captured :rf.error/http-reply-tail-failed)))
                    "surfaced exactly once")
                (is (= 1 (.get hits))
                    "the request reached the wire exactly once — the throw was NOT
                     reclassified as a transport rejection and retried")
                (is (= :rf.error/schemas-artefact-missing
                       (:reply-error-id (:tags (first (ops captured :rf.error/http-reply-tail-failed)))))
                    "the caught throw's own :rf.error/id rides the trace, which is
                     what distinguishes a completion-cascade throw from an :after throw")

                ;; The teardown the fence owes: a throw above `finalise-success!`
                ;; has not cleared the registry, so without the fence's teardown
                ;; the id stays in flight for ever and supersedes nothing.
                (is (settled? #(not (contains? (rf.http.registry/in-flight-snapshot) :fence/req))
                              "in-flight registry cleared")
                    "the in-flight registry entry was cleared by the fence's teardown")

                ;; No reply was delivered — there was no outcome to deliver.
                (is (nil? (:reply (rf/app-db-value :rf/default)))
                    "no reply landed (the completion cascade threw before any reply was built)")))))
        (finally (stop-server! srv))))))

;; ===========================================================================
;; The reply-tail error is observable in PRODUCTION
;; ===========================================================================

(deftest reply-tail-error-rides-the-always-on-axis
  (testing ":rf.error/http-reply-tail-failed reaches the ALWAYS-ON
            error-listener registry, not just the dev trace. Spec 014 §Failure
            mode promises a response-side throw is surfaced observably; an emit
            behind an outer interop/debug-enabled? gate would let a
            production CLJS bundle lose the reply silently"
    (let [hits     (AtomicInteger. 0)
          records  (atom [])
          srv      (start-counting-200-server! hits)]
      (try
        ;; The ALWAYS-ON axis — deliberately NOT trace.tooling/register-listener!.
        ;; The dev-trace assertion is green even with a dev-gated emit, so a
        ;; pin written against the trace bus proves nothing about production.
        (rf.error-emit/register-error-listener!
          ::recorder (fn [record] (swap! records conj record)))
        (rf/reg-http-interceptor :boom-after
          {:after (fn [_ctx _resp]
                    (throw (ex-info "reply-tail kaboom" {:detail :synthetic})))})
        (rf/reg-event :always-on/reply (fn [_ _] {}))
        (rf/reg-event :always-on/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request  {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                    :decode   :json
                    :reply-to [:always-on/reply]}]]}))
        (rf/dispatch-sync [:always-on/load])

        (let [tail? #(seq (filter (fn [r] (= :rf.error/http-reply-tail-failed (:error r)))
                                  @records))
              surfaced? (settled? tail? "always-on record for the reply-tail failure")]
          ;; ---- PRECONDITION --------------------------------------------
          ;; The request must actually have completed on the wire, so that the
          ;; REPLY TAIL is what threw. A request that never reached the server
          ;; could not have run an `:after` at all, and this pin would be
          ;; asserting nothing.
          (is (= 1 (.get hits))
              "PRECONDITION: the request completed on the wire exactly once, so the
               throw came from the reply tail of a SUCCEEDED request")

          ;; ---- VERDICT -------------------------------------------------
          (is surfaced?
              "the reply-tail failure reached the ALWAYS-ON error-listener registry
               — a dev-gated emit would leave this axis seeing nothing")
          (let [recs (filter (fn [r] (= :rf.error/http-reply-tail-failed (:error r))) @records)]
            (is (= 1 (count recs))
                "exactly one always-on record (no doubled emission)")))
        (finally
          (rf.error-emit/unregister-error-listener! ::recorder)
          (stop-server! srv))))))
