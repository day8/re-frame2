(ns re-frame.http-reply-lowering-test
  "Conformance for the EP-0011 managed-HTTP lowering (rf2-zqefg3.2): Spec 014
  `:rf.http/managed` lowered onto the uniform reply envelope
  (`spec/Managed-Effects.md` §The uniform reply envelope). Pins the
  EP-0011 §Validation groups for the HTTP slice:

    1. CANONICAL reply map — the transport's success / failure / abort facts
       become a single `re-frame.reply`-conformant reply map: one closed
       `:status`, `:work/id` `[:rf.work/http logical-id attempt]`,
       `:work/kind :http`, `:rf.reply/work-status`, `:correlation {:request-id …}`
       (the `:request-id` is correlation metadata, NOT a second stale key),
       `:completed-at` from the reply token. Timeout → `:status :error` +
       `:rf.reply/work-status :timed-out`; abort → `:status :cancelled` with an
       `:rf.http/aborted` `:error`.
    2. CANONICAL delivery — `:on-success` / `:on-failure` are pure routing
       sugar and the co-located `(:rf/reply msg)` merge both deliver the
       ONE canonical reply envelope verbatim (`{:status :ok :value v …}` /
       `{:status :error :error f …}`, rf2-ibksxg) — there is no
       `{:kind :success/:failure}` public reshape.
    3. SUPERSESSION — a same-`:request-id` supersede suppresses the prior
       request's app reply target (the supersede semantic is trace-only).

  Group 2 is exercised end-to-end through the real
  `java.net.http.HttpClient` transport (a tiny com.sun.net.httpserver test
  server); groups 1 + 3 are pinned at the pure / transport altitude.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope; EP-0011; rf2-ibksxg (one canonical async-reply envelope)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.reply :as http-reply]
            [re-frame.http.test-support]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.reply :as reply]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; ---- per-test reset (mirrors http_managed_test.clj) -----------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  ;; `clear-all!` wipes the framework's built-in `:rf/time-ms` reg-cofx
  ;; (registered at `re-frame.cofx` ns-load). Reload it so the provided
  ;; recordable coeffect is present for the EP-0017 reply-time tests
  ;; (rf2-5vdfrm) — a reply handler declaring `:rf.cofx/requires [:rf/time-ms]`
  ;; needs its supplier registered.
  (require 're-frame.cofx :reload)
  (require 're-frame.http.managed :reload)
  (require 're-frame.http.test-support :reload)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- real-transport server harness ---------------------------------------

(defn- start-server! [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx (reify HttpHandler (handle [_ ex] (handler ex))))
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

(defn- await-reply!
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until
     #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "http-reply-lowering"})))

;; ===========================================================================
;; Group 1 — the CANONICAL reply map (pure).
;; ===========================================================================

(def ^:private base-ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main
   :completed-at 1781078400456})

(deftest work-id-head
  (testing "HTTP work-id head is [:rf.work/http logical-id issuance attempt]"
    (is (= [:rf.work/http :article/by-id 1 1] (http-reply/work-id base-ctx)))
    (testing "logical-id falls back to the origin event-id when :request-id is absent"
      (is (= [:rf.work/http :article/load 1 1]
             (http-reply/work-id (dissoc base-ctx :request-id)))))
    (testing "the attempt slot discriminates transport retries within one issuance"
      (is (= [:rf.work/http :article/by-id 1 3]
             (http-reply/work-id (assoc base-ctx :attempt 3)))))
    (testing "the issuance slot discriminates re-issuances across supersessions (rf2-azcmd3)"
      ;; A superseded attempt (issuance 1) and its superseder (issuance 2) both
      ;; reset their retry :attempt to 1, but the issuance keeps their work
      ;; ids =-distinct — the EP-0011 one-attempt-one-work-id rule.
      (is (= [:rf.work/http :article/by-id 2 1]
             (http-reply/work-id (assoc base-ctx :issuance 2))))
      (is (not= (http-reply/work-id base-ctx)
                (http-reply/work-id (assoc base-ctx :issuance 2))))))
  (testing "the frame-qualified transport request-id is [:rf.req frame-id work-id]"
    (is (= [:rf.req :app/main [:rf.work/http :article/by-id 1 1]]
           (http-reply/transport-request-id :app/main (http-reply/work-id base-ctx))))))

(deftest success-reply-is-canonical
  (testing "a success completion builds a schema-valid :status :ok reply"
    (let [r (http-reply/success-reply base-ctx {:title "Welcome"})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :ok (:status r)))
      (is (= {:title "Welcome"} (:value r)))
      (is (= :completed (:rf.reply/work-status r)))
      (is (= :http (:rf.reply/work-kind r)))
      (is (= [:rf.work/http :article/by-id 1 1] (:rf.reply/work-id r)))
      (is (= :app/main (:rf.frame/id r)))
      (is (= 1781078400456 (:completed-at r)))
      (testing ":request-id rides as :correlation metadata, NOT a top-level stale key"
        (is (= {:request-id :article/by-id} (:correlation r)))
        (is (not (contains? r :request-id)))))))

(deftest failure-reply-maps-to-error
  (testing "a transport failure builds a schema-valid :status :error reply"
    (let [r (http-reply/failure-reply
              base-ctx {:kind :rf.http/http-5xx :status 503 :body "down"})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :error (:status r)))
      (is (= :failed (:rf.reply/work-status r)))
      (is (= :rf.http/http-5xx (get-in r [:error :kind])))
      (is (= 503 (get-in r [:error :status]))))))

(deftest timeout-maps-to-error-plus-timed-out-work-status
  (testing "timeout is :status :error + :rf.reply/work-status :timed-out (NOT a top-level status)"
    (let [r (http-reply/failure-reply
              base-ctx {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :error (:status r)) "timeout is NOT a top-level :status")
      (is (= :timed-out (:rf.reply/work-status r)))
      (is (= :rf.http/timeout (get-in r [:error :kind]))))))

(deftest abort-maps-to-cancelled
  (testing "an abort is :status :cancelled with an :rf.http/aborted :error"
    (let [failure {:kind :rf.http/aborted :reason :user :request-id :article/by-id}
          r       (http-reply/aborted-reply base-ctx failure)]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :cancelled (:status r)))
      (is (= :cancelled (:rf.reply/work-status r)))
      (is (true? (:cancelled? r)))
      (is (= :user (:rf.reply/cancel-reason r)))
      (is (= :rf.http/aborted (get-in r [:error :kind])))))
  (testing "an aborted failure routed through failure-reply also lowers to :cancelled"
    (let [r (http-reply/failure-reply
              base-ctx {:kind :rf.http/aborted :reason :actor-destroyed})]
      (is (= :cancelled (:status r)))
      (is (= :actor-destroyed (:rf.reply/cancel-reason r))))))

;; ===========================================================================
;; Group 1b — the SHARED-TARGET lowering path + the reply-mapping functor law
;; for HTTP (rf2-9u1tvq). HTTP's reply addressing — append the CANONICAL reply
;; envelope as the final arg of the reply target's event (rf2-ibksxg: no
;; compat reshape) — IS the shared `re-frame.reply/complete` `:delivery
;; :append`. These pin that HTTP rides the shared target functions (`complete`
;; / `map-completed-event`) rather than a family-private append, and that the
;; EP-0011 functor law holds over HTTP's reply target — not only that the
;; canonical reply map is constructed.
;; ===========================================================================

(deftest http-target-lowers-through-shared-complete
  (testing "the HTTP reply (canonical envelope appended to the target event) IS re-frame.reply/complete :delivery :append — the shared-target lowering path, not a family-private append"
    (let [reply  (http-reply/success-reply base-ctx {:title "Welcome"})
          target [:article/load {:id 42}]]
      ;; `complete` appends the canonical reply as the final arg — exactly the
      ;; shape `build-reply-event` produces for an explicit reply target.
      (is (= [:article/load {:id 42} reply]
             (reply/complete target reply)))
      (is (= :ok (get-in (reply/complete target reply) [2 :status])))
      (testing "a failure reply lowers the same way through the shared append"
        (let [fr (http-reply/failure-reply base-ctx {:kind :rf.http/http-5xx :status 503 :body "down"})]
          (is (= [:svc/failed fr] (reply/complete [:svc/failed] fr)))
          (is (= :rf.http/http-5xx (get-in (reply/complete [:svc/failed] fr) [1 :error :kind]))))))))

(deftest http-target-satisfies-functor-law
  (testing "rf2-9u1tvq — the EP-0011 reply-mapping functor law holds over HTTP's reply target: complete(map-completed-event(f,t),reply) == f(complete(t,reply)); identity + composition"
    (let [payload (http-reply/success-reply base-ctx {:title "Welcome"})
          target  [:article/load {:id 42}]
          ;; relocate the completed reply into a wrapper event (the Cmd.map role)
          wrap    (fn [ev] [:wrap ev])
          tag     (fn [ev] (conj ev :tag))]
      (testing "the law: complete(map-completed-event(f, target), reply) == f(complete(target, reply))"
        (is (= (reply/complete (reply/map-completed-event wrap target) payload)
               (wrap (reply/complete target payload)))))
      (testing "identity: map-completed-event(identity, target) completes to the plain completion"
        (is (= (reply/complete (reply/map-completed-event identity target) payload)
               (reply/complete target payload))))
      (testing "composition: map-completed-event(comp f g) == map-completed-event f ∘ map-completed-event g"
        (is (= (reply/complete (reply/map-completed-event (comp wrap tag) target) payload)
               (reply/complete (reply/map-completed-event wrap (reply/map-completed-event tag target)) payload))))
      (testing "mapping changes ONLY the completed event — the canonical reply facts (work-id / status) are untouched by map-completed-event"
        (let [reply (http-reply/success-reply base-ctx {:title "Welcome"})]
          ;; work-id / status are not stored on the target, so mapping cannot
          ;; touch them — the law is structural.
          (is (= [:rf.work/http :article/by-id 1 1] (:rf.reply/work-id reply)))
          (is (= :ok (:status reply))))))))

;; ===========================================================================
;; Group 2a — the compat reshape is GONE (rf2-ibksxg).
;; ===========================================================================

(deftest no-public-payload-reshape
  (testing "rf2-ibksxg — the {:kind :success/:failure} reshape (reply->public-payload / :rf.http/compat-reply) is DELETED; the canonical reply IS the public payload"
    (is (not (contains? (ns-publics 're-frame.http.reply) 'reply->public-payload)))))

;; ===========================================================================
;; Group 2b — the CANONICAL envelope is delivered END-TO-END through the real
;; transport (rf2-ibksxg — one dialect, no reshape).
;; ===========================================================================

(deftest real-transport-default-reply-delivers-canonical-envelope
  (testing "co-located (:rf/reply msg) default receives the canonical {:status :ok :value v …} envelope"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 200 "application/json"
                                   "{\"title\":\"hello\",\"id\":42}")))]
      (try
        (rf/reg-event :article/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/a")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:article/load {}])
        (let [db (await-reply! #(some? (:reply %)))]
          ;; The canonical reply envelope — the ONE dialect delivered to the app.
          (is (= :ok (get-in db [:reply :status])))
          (is (= "hello" (get-in db [:reply :value :title])))
          (is (= :completed (get-in db [:reply :rf.reply/work-status])))
          (is (= :http (get-in db [:reply :rf.reply/work-kind])))
          ;; the retired {:kind :success} dialect is GONE
          (is (not (contains? (:reply db) :kind))))
        (finally (stop-server! srv))))))

(deftest real-transport-explicit-on-failure-delivers-canonical-envelope
  (testing "explicit :on-failure receives the canonical {:status :error :error {:kind :rf.http/http-5xx …} …} envelope"
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (write-response! ex 503 "text/plain" "down")))]
      (try
        (rf/reg-event :svc/call
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                    :on-failure [:svc/failed]}]]}))
        (rf/reg-event :svc/failed (fn [{:keys [db]} [_ payload]] {:db (assoc db :got payload)}))
        (rf/dispatch-sync [:svc/call])
        (let [db (await-reply! #(some? (:got %)))]
          (is (= :error (get-in db [:got :status])))
          (is (= :failed (get-in db [:got :rf.reply/work-status])))
          ;; the classified :rf.http/* failure map rides VERBATIM under :error
          (is (= :rf.http/http-5xx (get-in db [:got :error :kind])))
          (is (= 503 (get-in db [:got :error :status])))
          (is (not (contains? (:got db) :kind)) "no retired :kind dialect"))
        (finally (stop-server! srv))))))

;; ===========================================================================
;; Group 2c — rf2-1u9dja: the public failure `:error` map is SELF-IDENTIFYING.
;; Every failure category carries :request {:method :url}, :request-id,
;; :attempt/:max-attempts, and :work/id — WHICH request failed, not only what
;; kind of failure it was (debugging-dx finding 3). Exercised end-to-end
;; through the real transport for the categories a test server can trigger.
;; ===========================================================================

(defn- fetch-failure-error
  "Issue one managed GET against `url` (retry `max-attempts`, `request-id`),
  drive it, and return the `:error` map of the delivered failure reply."
  [url {:keys [request-id max-attempts]}]
  (rf/reg-event :sid/call
    (fn [_ _]
      {:fx [[:rf.http/managed
             (cond-> {:request    {:method :get :url url}
                      :decode     :json
                      :on-failure [:sid/failed]}
               request-id   (assoc :request-id request-id)
               max-attempts (assoc :retry {:on #{:rf.http/http-5xx :rf.http/transport}
                                           :max-attempts max-attempts
                                           :backoff {:base-ms 1 :factor 1 :max-ms 1}}))]]}))
  (rf/reg-event :sid/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :reply reply)}))
  (rf/dispatch-sync [:sid/call])
  (get-in (await-reply! #(some? (:reply %))) [:reply :error]))

(deftest failure-reply-is-self-identifying-4xx
  (testing "an :rf.http/http-4xx failure echoes :request/:request-id/:attempt/:work-id"
    (let [srv (start-server! (fn [^HttpExchange ex] (write-response! ex 404 "text/plain" "nope")))]
      (try
        (let [url (str "http://127.0.0.1:" (:port srv) "/gone")
              err (fetch-failure-error url {:request-id :sid/get})]
          (is (= :rf.http/http-4xx (:kind err)) "category tag survives")
          (is (= {:method :get :url url} (:request err)) "echoes the caller's wire envelope")
          (is (= :sid/get (:request-id err)) ":request-id is uniform (not aborted-only)")
          (is (= 1 (:attempt err)))
          (is (= [:rf.work/http :sid/get 1 1] (:work/id err)) "correlation join to the trace")
          ;; category-specific tags still ride
          (is (= 404 (:status err))))
        (finally (stop-server! srv))))))

(deftest failure-reply-is-self-identifying-transport
  (testing "an :rf.http/transport failure (connection refused) is self-identifying"
    ;; Port 1 is reliably closed — connection refused → :rf.http/transport.
    (let [url "http://127.0.0.1:1/x"
          err (fetch-failure-error url {:request-id :sid/xport})]
      (is (= :rf.http/transport (:kind err)))
      (is (= {:method :get :url url} (:request err)))
      (is (= :sid/xport (:request-id err)))
      (is (= [:rf.work/http :sid/xport 1 1] (:work/id err))))))

(deftest failure-reply-is-self-identifying-5xx-with-retry
  (testing "an :rf.http/http-5xx failure after exhausted retries carries :attempt/:max-attempts"
    (let [srv (start-server! (fn [^HttpExchange ex] (write-response! ex 503 "text/plain" "down")))]
      (try
        (let [url (str "http://127.0.0.1:" (:port srv) "/down")
              err (fetch-failure-error url {:request-id :sid/five-xx :max-attempts 3})]
          (is (= :rf.http/http-5xx (:kind err)))
          (is (= {:method :get :url url} (:request err)))
          (is (= 3 (:attempt err)) "the final (third) attempt is reported")
          (is (= 3 (:max-attempts err)) "the retry ceiling rides the failure")
          (is (= [:rf.work/http :sid/five-xx 1 3] (:work/id err))
              "the work-id's attempt slot matches the exhausting attempt"))
        (finally (stop-server! srv))))))

(deftest failure-reply-is-self-identifying-decode
  (testing "an :rf.http/decode-failure (malformed JSON on 200) is self-identifying"
    (let [srv (start-server! (fn [^HttpExchange ex] (write-response! ex 200 "application/json" "{not json")))]
      (try
        (let [url (str "http://127.0.0.1:" (:port srv) "/bad")
              err (fetch-failure-error url {:request-id :sid/decode})]
          (is (= :rf.http/decode-failure (:kind err)))
          (is (= {:method :get :url url} (:request err)))
          (is (= :sid/decode (:request-id err)))
          (is (= [:rf.work/http :sid/decode 1 1] (:work/id err))))
        (finally (stop-server! srv))))))

(deftest failure-reply-is-self-identifying-aborted
  (testing "an :rf.http/aborted reply (:status :cancelled) is self-identifying too"
    (let [gate (java.util.concurrent.CountDownLatch. 1)
          srv  (start-server!
                 (fn [^HttpExchange ex]
                   (try (.await gate 2 java.util.concurrent.TimeUnit/SECONDS)
                        (catch InterruptedException _ nil))
                   (write-response! ex 200 "application/json" "{\"v\":1}")))]
      (try
        (let [url (str "http://127.0.0.1:" (:port srv) "/slow")]
          (rf/reg-event :sid/go
            (fn [_ _]
              {:fx [[:rf.http/managed
                     {:request    {:method :get :url url}
                      :request-id :sid/abort
                      :decode     :json
                      :on-failure [:sid/failed]}]]}))
          (rf/reg-event :sid/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :reply reply)}))
          (rf/reg-event :sid/abort! (fn [_ _] {:fx [[:rf.http/managed-abort :sid/abort]]}))
          (rf/dispatch-sync [:sid/go])
          (rf/dispatch-sync [:sid/abort!])
          (.countDown gate)
          (let [reply (get-in (await-reply! #(some? (:reply %))) [:reply])
                err   (:error reply)]
            (is (= :cancelled (:status reply)) "abort is a :status :cancelled reply")
            (is (= :rf.http/aborted (:kind err)))
            (is (= {:method :get :url url} (:request err)))
            (is (= :sid/abort (:request-id err)))
            (is (= [:rf.work/http :sid/abort 1 1] (:work/id err)))))
        (finally (stop-server! srv))))))

(deftest real-transport-emits-canonical-replied-trace
  (testing "completion emits a :rf.http/replied trace row built from the canonical envelope facts"
    (let [srv     (start-server!
                    (fn [^HttpExchange ex]
                      (write-response! ex 200 "application/json" "{\"ok\":true}")))
          traces  (atom [])
          lid     ::replied-trace]
      (try
        (trace/register-listener! lid (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :t/load
          (fn [{:keys [db]} [_ msg]]
            (if (:rf/reply msg)
              {:db (assoc db :done true)}
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" (:port srv) "/t")}
                      :request-id :t/load
                      :decode     :json}]]})))
        (rf/dispatch-sync [:t/load {}])
        (await-reply! #(:done %))
        (let [replied (filter #(= :rf.http/replied (:operation %)) @traces)]
          (is (seq replied) "a :rf.http/replied trace row was emitted")
          (let [tags (:tags (first replied))]
            ;; identity facts ride verbatim on the canonical trace summary
            (is (= :ok (:status tags)))
            (is (= :http (:rf.reply/work-kind tags)))
            (is (= [:rf.work/http :t/load 1 1] (:rf.reply/work-id tags)))
            ;; :request-id is correlation metadata, not a second stale key
            (is (= {:request-id :t/load} (:correlation tags)))))
        (finally
          (trace/unregister-listener! lid)
          (stop-server! srv))))))

;; ===========================================================================
;; Group 3 — supersession suppresses the prior request's app target.
;; ===========================================================================

(deftest supersede-suppresses-prior-app-reply
  (testing "a same-:request-id supersede suppresses the FIRST request's :on-failure app target"
    ;; A slow server keeps request #1 in flight; issuing request #2 with the
    ;; same :request-id supersedes #1. Per Spec 014 §`:request-id` (internal)
    ;; the superseded request's reply is trace-only — its app target MUST NOT
    ;; fire. #2 completes normally and IS delivered.
    (let [gate (java.util.concurrent.CountDownLatch. 1)
          srv  (start-server!
                 (fn [^HttpExchange ex]
                   ;; Block the FIRST connection until the gate opens; the
                   ;; SECOND request opens the gate so both eventually drain.
                   (.countDown gate)
                   (try (.await gate 2 java.util.concurrent.TimeUnit/SECONDS)
                        (catch InterruptedException _ nil))
                   (write-response! ex 200 "application/json" "{\"v\":1}")))
          replies (atom [])]
      (try
        (rf/reg-event :search/replied
          (fn [{:keys [db]} [_ payload]]
            (swap! replies conj payload)
            {:db db}))
        (rf/reg-event :search/go
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/s")}
                    :request-id :search
                    :decode     :json
                    :on-success [:search/replied]
                    :on-failure [:search/replied]}]]}))
        ;; Fire #1, then #2 (supersedes #1). dispatch-sync drains each event.
        (rf/dispatch-sync [:search/go])
        (rf/dispatch-sync [:search/go])
        ;; Open the gate so any in-flight transport drains, then wait for at
        ;; least one delivered reply.
        (.countDown gate)
        (test-support/poll-until
          #(when (seq @replies) true)
          {:timeout-ms 5000 :label "supersede"})
        (Thread/sleep 200) ;; quiescence window for any (wrongly) delivered #1 reply
        ;; The superseded request #1's app target is NOT dispatched: exactly
        ;; one delivered reply (request #2's), never the supersede of #1.
        (is (= 1 (count @replies))
            "only the surviving request's reply is delivered; the superseded one is suppressed")
        (is (= :ok (:status (first @replies)))
            "the surviving reply is request #2's canonical :status :ok success")
        (finally (stop-server! srv))))))

(deftest supersede-distinct-work-ids-and-canonical-stale-trace
  (testing "rf2-azcmd3 — superseded + superseding attempts have DISTINCT :work/id, and the superseded one records a canonical :status :stale / :rf.reply/work-status :suppressed reply-envelope trace with carried/current correlation; only the new app reply fires"
    (let [gate    (java.util.concurrent.CountDownLatch. 1)
          srv     (start-server!
                    (fn [^HttpExchange ex]
                      (.countDown gate)
                      (try (.await gate 2 java.util.concurrent.TimeUnit/SECONDS)
                           (catch InterruptedException _ nil))
                      (write-response! ex 200 "application/json" "{\"v\":1}")))
          replies (atom [])
          traces  (atom [])
          lid     ::supersede-stale]
      (try
        (trace/register-listener! lid (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :search/replied
          (fn [{:keys [db]} [_ payload]] (swap! replies conj payload) {:db db}))
        (rf/reg-event :search/go
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/s")}
                    :request-id :search
                    :decode     :json
                    :on-success [:search/replied]
                    :on-failure [:search/replied]}]]}))
        ;; #1 (issuance 1) goes in flight; #2 (issuance 2) supersedes it.
        (rf/dispatch-sync [:search/go])
        (rf/dispatch-sync [:search/go])
        (.countDown gate)
        (test-support/poll-until
          (fn []
            (when (and (seq @replies)
                       (some (fn [ev] (= :rf.http/stale-suppressed (:operation ev))) @traces))
              true))
          {:timeout-ms 5000 :label "supersede-stale"})
        (Thread/sleep 200)
        ;; Exactly one DELIVERED app reply — request #2's; #1 suppressed.
        (is (= 1 (count @replies))
            "only the surviving request's app reply is delivered")
        ;; The superseded attempt records a canonical stale reply-envelope row.
        (let [stale (filter #(= :rf.http/stale-suppressed (:operation %)) @traces)]
          (is (= 1 (count stale)) "exactly one stale-suppression row for the superseded attempt")
          (let [tags (:tags (first stale))]
            (is (= :stale (:rf.reply/status tags)))
            (is (= :suppressed (:rf.reply/work-status tags)))
            (is (= :rf.http/request-id-superseded (:rf.reply/stale-reason tags)))
            (is (= :http (:rf.reply/work-kind tags)))
            ;; Carried = the superseded attempt's work-id (issuance 1);
            ;; current = the superseding attempt's work-id (issuance 2). The
            ;; two are =-distinct — tooling can tell them apart by :work/id.
            (is (= [:rf.work/http :search 1 1] (:work/id (:rf.reply/carried tags))))
            (is (= [:rf.work/http :search 2 1] (:work/id (:rf.reply/current tags))))
            (is (not= (:work/id (:rf.reply/carried tags))
                      (:work/id (:rf.reply/current tags))))
            ;; The canonical join key reads the carried (superseded) work-id.
            (is (= [:rf.work/http :search 1 1] (:rf.reply/work-id tags)))))
        (finally
          (trace/unregister-listener! lid)
          (stop-server! srv))))))

;; ===========================================================================
;; Group 4 — actor-destroy obsolete-target stale suppression (rf2-yrrpe2,
;; pure altitude). Managed-Effects §Cancellation: an actor-destroy abort whose
;; reply target addresses the destroyed actor itself is OBSOLETE and lowers to
;; `:status :stale` / `:rf.reply/work-status :suppressed` (no app delivery); a target
;; naming an ordinary event is still meaningful and stays a live `:cancelled`.
;; ===========================================================================

(deftest actor-destroy-target-obsolete-predicate
  (testing "rf2-yrrpe2 — a reply target naming the destroyed actor itself is obsolete; an ordinary target is meaningful"
    (is (true?  (http-reply/actor-destroy-target-obsolete? :worker/proc#1 :worker/proc#1))
        "target == actor-id (machine-shape wrapper's [self-id ...]) → obsolete")
    (is (false? (http-reply/actor-destroy-target-obsolete? :reply/recorder :worker/proc#1))
        "target is an ordinary event (≠ actor-id) → still meaningful")
    (is (false? (http-reply/actor-destroy-target-obsolete? :worker/proc#1 nil))
        "no actor binding → never obsolete")
    (is (false? (http-reply/actor-destroy-target-obsolete? nil :worker/proc#1))
        "no resolvable target → not classified obsolete")))

(deftest actor-destroy-suppress-is-canonical-stale
  (testing "rf2-yrrpe2 — an obsolete actor-bound completion lowers to the shared :status :stale / :rf.reply/work-status :suppressed outcome, no app delivery"
    (let [ctx {:request-id   [:worker/proc#1 :slow]
               :origin-event [:worker/proc#1 [:rf.http/failed]]
               :issuance     1
               :attempt      1
               :frame        :app/main}
          {:keys [deliver? reply trace] :as out} (http-reply/actor-destroy-suppress ctx)]
      (is (false? deliver?) "the obsolete actor-bound app target MUST NOT run")
      (is (= :suppressed (:rf.reply/work-status out)))
      (is (reply/valid-reply? reply) (str (reply/validate-reply reply)))
      (is (= :stale (:status reply)))
      (is (true? (:stale? reply)))
      (is (= :rf.http/actor-destroyed-target-obsolete (:rf.reply/stale-reason reply)))
      (is (= :suppressed (:rf.reply/work-status reply)))
      (is (not (contains? reply :value)) "a stale reply MUST NOT carry :value")
      (testing "the trace joins the carried work-id"
        (is (= [:rf.work/http [:worker/proc#1 :slow] 1 1]
               (:work/id (:rf.reply/carried trace))))
        (is (nil? (:rf.reply/current trace))
            "no live successor — the actor that owned the target is gone")))))

;; ===========================================================================
;; Group 5 — EP-0017 HTTP-reply :rf.cofx time delivery (rf2-5vdfrm). The HTTP
;; completion time (read ONCE at finalisation in `reply-ctx`) rides the reply
;; dispatch's flat `:rf.cofx` `:rf/time-ms`. A reply handler DECLARING
;; `{:rf.cofx/requires [:rf/time-ms]}` receives EXACTLY that HTTP completion
;; timestamp; an UNDECLARED reply handler does NOT see implicit `:rf/time-ms`
;; flat. This is the HTTP-owned boundary that supplies host completion time as
;; the reply token's recordable cofx — distinct from the core
;; provided-cofx-delivery conformance.
;;
;; The test fails if `dispatch-reply-via-late-bind!` stops supplying flat
;; `:rf.cofx`, regresses to the retired `:rf.world/inputs`, or relies on an
;; implicit fresh-clock read (the declared handler would then see a value
;; != the HTTP `:completed-at`, or the undeclared handler would see one).
;; ===========================================================================

(deftest http-reply-declared-handler-receives-completion-time-flat
  (testing "rf2-5vdfrm — a reply handler declaring {:rf.cofx/requires [:rf/time-ms]} receives the HTTP completion timestamp (== the :rf.http/replied trace :completed-at) flat under :rf/time-ms; the reply dispatch carries it on a flat :rf.cofx supplied BY HTTP (not the router's enqueue fill)"
    (let [srv    (start-server!
                   (fn [^HttpExchange ex]
                     (write-response! ex 200 "application/json" "{\"v\":1}")))
          cofx   (atom ::unset)
          traces (atom [])
          ;; Capture the :rf.cofx OPT the http reply path passes to
          ;; `:router/dispatch!`. This directly pins that HTTP SUPPLIES a flat
          ;; `:rf.cofx` `:rf/time-ms` on the reply dispatch (the bead's literal
          ;; failure condition: "fails if dispatch-reply-via-late-bind! stops
          ;; supplying flat :rf.cofx"), rather than relying on the router's
          ;; missing-cofx enqueue fill — whose value coincides with
          ;; :completed-at to the millisecond and so cannot discriminate.
          reply-dispatch-opts (atom nil)
          real-dispatch! (late-bind/get-fn :router/dispatch!)
          lid    ::cofx-time]
      (try
        (late-bind/set-fn! :router/dispatch!
          (fn [ev opts]
            (when (= :svc/replied (first ev))
              (reset! reply-dispatch-opts opts))
            (real-dispatch! ev opts)))
        (trace/register-listener! lid (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :svc/call
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/c")}
                    :request-id :svc/call
                    :decode     :json
                    :on-success [:svc/replied]}]]}))
        ;; The reply handler DECLARES the recordable fact, so it is delivered
        ;; FLAT under its id (EP-0017 §2/§5). The handler reads it off the
        ;; reply token's recordable coeffects — NOT a fresh ambient clock.
        (rf/reg-event :svc/replied
          {:rf.cofx/requires [:rf/time-ms]}
          (fn [{:keys [db] :as coeffects} _]
            (reset! cofx coeffects)
            {:db (assoc db :done true)}))
        (rf/dispatch-sync [:svc/call])
        (let [db (await-reply! #(:done %))
              c  @cofx
              replied (->> @traces
                           (filter #(= :rf.http/replied (:operation %)))
                           first)
              trace-completed-at (get-in replied [:tags :completed-at])
              opts @reply-dispatch-opts]
          (is (some? db))
          ;; (1) HTTP supplies a flat :rf.cofx :rf/time-ms on the reply dispatch.
          (is (= {:rf/time-ms trace-completed-at} (:rf.cofx opts))
              "the http reply dispatch SUPPLIES a flat :rf.cofx {:rf/time-ms <completed-at>} — not omitted, not nested")
          (is (= :http (:source opts))
              "the reply dispatch self-tags :source :http")
          ;; (2) the declared handler receives that completion time FLAT.
          (is (number? (:rf/time-ms c))
              "the declared recordable fact arrived FLAT under :rf/time-ms")
          (is (some? trace-completed-at)
              "the :rf.http/replied trace carries the canonical :completed-at")
          (is (= trace-completed-at (:rf/time-ms c))
              "the handler's :rf/time-ms IS the HTTP completion time (read once in reply-ctx), not a fresh clock read")
          (testing "the flat :rf.cofx record carries the same fact (the EP-0017 envelope field)"
            (is (= trace-completed-at (get (:rf.cofx c) :rf/time-ms)))
            (is (not (contains? c :rf.world/inputs))
                "the retired :rf.world/inputs nested envelope is GONE (flat rename)")))
        (finally
          (late-bind/set-fn! :router/dispatch! real-dispatch!)
          (trace/unregister-listener! lid)
          (stop-server! srv))))))

(deftest http-reply-undeclared-handler-does-not-see-implicit-time
  (testing "rf2-5vdfrm — an UNDECLARED reply handler does NOT receive :rf/time-ms flat (no implicit time delivery); the fact rides :rf.cofx but is not handed flat without a declaration"
    (let [srv    (start-server!
                   (fn [^HttpExchange ex]
                     (write-response! ex 200 "application/json" "{\"v\":1}")))
          cofx   (atom ::unset)]
      (try
        (rf/reg-event :svc/call2
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/c")}
                    :decode     :json
                    :on-success [:svc/replied2]}]]}))
        ;; NO :rf.cofx/requires — the handler must NOT see :rf/time-ms flat.
        (rf/reg-event :svc/replied2
          (fn [{:keys [db] :as coeffects} _]
            (reset! cofx coeffects)
            {:db (assoc db :done2 true)}))
        (rf/dispatch-sync [:svc/call2])
        (await-reply! #(:done2 %))
        (let [c @cofx]
          (is (not (contains? c :rf/time-ms))
              "an undeclared reply handler does NOT receive :rf/time-ms flat (no implicit time delivery)")
          (is (map? (:rf.cofx c))
              "the flat :rf.cofx record is still reachable for generic code (EP-0017 §5)")
          (is (not (contains? c :rf.world/inputs))
              "the retired :rf.world/inputs nested envelope is GONE"))
        (finally (stop-server! srv))))))
