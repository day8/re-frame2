(ns re-frame.http-reply-lowering-test
  "Conformance for the EP-0011 managed-HTTP lowering (rf2-zqefg3.2): Spec 014
  `:rf.http/managed` lowered onto the uniform reply envelope
  (`spec/Managed-Effects.md` §The uniform reply envelope). Pins the
  EP-0011 §Validation groups for the HTTP slice:

    1. CANONICAL reply map — the transport's success / failure / abort facts
       become a single `re-frame.reply`-conformant reply map: one closed
       `:status`, `:work/id` `[:rf.work/http logical-id attempt]`,
       `:work/kind :http`, `:work/status`, `:correlation {:request-id …}`
       (the `:request-id` is correlation metadata, NOT a second stale key),
       `:completed-at` from the reply token. Timeout → `:status :error` +
       `:work/status :timed-out`; abort → `:status :cancelled` with an
       `:rf.http/aborted` `:error`.
    2. PUBLIC compatibility reshape — `:on-success` / `:on-failure` and the
       co-located `(:rf/reply msg)` merge keep the exact Spec 014 §Reply
       payload shapes (`{:kind :success :value v}` / `{:kind :failure
       :failure f}`) even though the request lowered through the envelope.
    3. SUPERSESSION — a same-`:request-id` supersede suppresses the prior
       request's app reply target (the supersede semantic is trace-only).

  Group 2 is exercised end-to-end through the real
  `java.net.http.HttpClient` transport (a tiny com.sun.net.httpserver test
  server); groups 1 + 3 are pinned at the pure / transport altitude.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope; EP-0011 §Managed HTTP Lowering / §Public Compatibility Sugar."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.http-managed :as http-managed]
            [re-frame.http-reply :as http-reply]
            [re-frame.http-test-support]
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
  (require 're-frame.http-managed :reload)
  (require 're-frame.http-test-support :reload)
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
      (is (= :completed (:work/status r)))
      (is (= :http (:work/kind r)))
      (is (= [:rf.work/http :article/by-id 1 1] (:work/id r)))
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
      (is (= :failed (:work/status r)))
      (is (= :rf.http/http-5xx (get-in r [:error :kind])))
      (is (= 503 (get-in r [:error :status]))))))

(deftest timeout-maps-to-error-plus-timed-out-work-status
  (testing "timeout is :status :error + :work/status :timed-out (NOT a top-level status)"
    (let [r (http-reply/failure-reply
              base-ctx {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :error (:status r)) "timeout is NOT a top-level :status")
      (is (= :timed-out (:work/status r)))
      (is (= :rf.http/timeout (get-in r [:error :kind]))))))

(deftest abort-maps-to-cancelled
  (testing "an abort is :status :cancelled with an :rf.http/aborted :error"
    (let [failure {:kind :rf.http/aborted :reason :user :request-id :article/by-id}
          r       (http-reply/aborted-reply base-ctx failure)]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :cancelled (:status r)))
      (is (= :cancelled (:work/status r)))
      (is (true? (:cancelled? r)))
      (is (= :user (:cancel/reason r)))
      (is (= :rf.http/aborted (get-in r [:error :kind])))))
  (testing "an aborted failure routed through failure-reply also lowers to :cancelled"
    (let [r (http-reply/failure-reply
              base-ctx {:kind :rf.http/aborted :reason :actor-destroyed})]
      (is (= :cancelled (:status r)))
      (is (= :actor-destroyed (:cancel/reason r))))))

;; ===========================================================================
;; Group 2a — public compatibility reshape (pure inverse projection).
;; ===========================================================================

(deftest reshape-preserves-public-shapes
  (testing ":ok reshapes to {:kind :success :value v}"
    (is (= {:kind :success :value {:title "Welcome"}}
           (http-reply/reply->public-payload
             (http-reply/success-reply base-ctx {:title "Welcome"})))))
  (testing ":error reshapes to {:kind :failure :failure f} carrying the :rf.http/* failure"
    (let [failure {:kind :rf.http/http-4xx :status 404 :body "nope"}]
      (is (= {:kind :failure :failure failure}
             (http-reply/reply->public-payload
               (http-reply/failure-reply base-ctx failure))))))
  (testing ":cancelled reshapes to a {:kind :failure …} reply carrying :rf.http/aborted"
    (let [failure {:kind :rf.http/aborted :reason :user}]
      (is (= {:kind :failure :failure failure}
             (http-reply/reply->public-payload
               (http-reply/aborted-reply base-ctx failure))))))
  (testing ":stale reshapes to nil — a suppressed reply is never delivered to the app target"
    (is (nil? (http-reply/reply->public-payload
                {:status :stale :stale? true :stale/reason :x})))))

;; ===========================================================================
;; Group 2b — public shape preserved END-TO-END through the real transport.
;; ===========================================================================

(deftest real-transport-default-reply-preserves-public-shape
  (testing "co-located (:rf/reply msg) default still receives {:kind :success :value v}"
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
          ;; The PUBLIC Spec 014 §Reply payload shape — unchanged by the
          ;; internal lowering.
          (is (= :success (get-in db [:reply :kind])))
          (is (= "hello"  (get-in db [:reply :value :title])))
          ;; The canonical envelope facts (:status / :work/id / :completed-at)
          ;; are INTERNAL — they MUST NOT leak into the public payload.
          (is (not (contains? (:reply db) :status)))
          (is (not (contains? (:reply db) :work/id))))
        (finally (stop-server! srv))))))

(deftest real-transport-explicit-on-failure-preserves-public-shape
  (testing "explicit :on-failure still receives {:kind :failure :failure {:kind :rf.http/http-5xx …}}"
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
          (is (= :failure (get-in db [:got :kind])))
          (is (= :rf.http/http-5xx (get-in db [:got :failure :kind])))
          (is (= 503 (get-in db [:got :failure :status])))
          (is (not (contains? (:got db) :status)) "no canonical envelope leak"))
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
            (is (= :http (:work/kind tags)))
            (is (= [:rf.work/http :t/load 1 1] (:work/id tags)))
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
        (is (= :success (:kind (first @replies)))
            "the surviving reply is request #2's success")
        (finally (stop-server! srv))))))

(deftest supersede-distinct-work-ids-and-canonical-stale-trace
  (testing "rf2-azcmd3 — superseded + superseding attempts have DISTINCT :work/id, and the superseded one records a canonical :status :stale / :work/status :suppressed reply-envelope trace with carried/current correlation; only the new app reply fires"
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
            (is (= :http (:work/kind tags)))
            ;; Carried = the superseded attempt's work-id (issuance 1);
            ;; current = the superseding attempt's work-id (issuance 2). The
            ;; two are =-distinct — tooling can tell them apart by :work/id.
            (is (= [:rf.work/http :search 1 1] (:work/id (:rf.reply/carried tags))))
            (is (= [:rf.work/http :search 2 1] (:work/id (:rf.reply/current tags))))
            (is (not= (:work/id (:rf.reply/carried tags))
                      (:work/id (:rf.reply/current tags))))
            ;; The canonical join key reads the carried (superseded) work-id.
            (is (= [:rf.work/http :search 1 1] (:work/id tags)))))
        (finally
          (trace/unregister-listener! lid)
          (stop-server! srv))))))
