(ns re-frame.http-abort-config-validation-test
  "Spec 014 §`:abort-signal` (external) — `:abort-signal` and `:request-id`
  are NOT mutually exclusive (rf2-q8vbna). A request may carry BOTH; each
  attaches a cancellation source to the ONE managed request.

  The earlier `validate-abort-config!` guard rejected the combination at
  the dispatch site with a thrown `:rf.error/http-bad-abort-config`,
  rationalised as an undefined-by-spec simultaneous-abort race. That race
  is not real: the CLJS transport forwards an external `:abort-signal`
  into the SAME framework-owned internal controller the `:request-id`
  supersede/managed-abort path drives (the single signal Fetch accepts is
  always the internal one), and the once-only `:finalised?` CAS
  (rf2-on7sj) guarantees EXACTLY ONE terminal outcome regardless of which
  cancellation source acts first. Abort wins by classification, not race
  ordering (Spec 014 §Abort precedence). The guard rejected a
  fully-defined, working configuration on a false premise, and is removed.

  These tests pin the post-removal contract:

   1. both-supplied does NOT throw (no `:rf.error/http-bad-abort-config`
      ex-info, no throw at all) — the dual-source config is legal.
   2. the once-only CAS still yields AT MOST ONE terminal outcome with
      both keys present, exercising BOTH finalise orders:
        a. user-abort-first (the path an external `:abort-signal` funnels
           into) => exactly one `:rf.http/aborted` `:reason :user` reply;
        b. supersede-first => the superseded attempt's reply is
           SUPPRESSED (no app target runs) and a `:rf.http/stale-suppressed`
           trace records the supersession; the superseding attempt yields
           its own single outcome.

  JVM caveat: `:abort-signal` is CLJS-only (the JVM transport ignores it
  with a `:rf.http/cljs-only-key-ignored-on-jvm` degradation trace), so
  these JVM tests drive cancellation through the portable `:request-id`
  path while ALSO supplying `:abort-signal` — the exact dual-source shape
  the removed guard rejected — and assert it is accepted and
  single-outcome."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.http.handlers :as handlers]
            [re-frame.http.managed :as http-managed]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`, and
  ;; the managed-HTTP fxs now require a carried frame stamp. This suite
  ;; exercises the ambient dispatch path against a single conventional app
  ;; frame, so register `:rf/default` explicitly and pin it as the
  ;; established scope for the whole body via `with-frame`.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http.managed :reload)
  (http-managed/clear-all-in-flight!)
  (http-managed/clear-all-http-interceptors!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- call-managed!
  "Invoke `:rf.http/managed` via the public handler with the given full
  args-map. Returns nil on success / no-throw, or the ex-info on a throw."
  [args-map]
  (try (handlers/managed-handler {:frame :rf/default :event [:no-op]}
                                 args-map)
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- bad-abort-config-throw?
  "True when the call threw the (removed) `:rf.error/http-bad-abort-config`."
  [ex]
  (and (some? ex)
       (= :rf.error/http-bad-abort-config (:rf.error/id (ex-data ex)))))

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "timed out awaiting condition" {}))
         :else (do (Thread/sleep 10) (recur)))))))

(defn- start-blocking-server!
  "Start an HttpServer whose handler blocks on `release` before answering
  200, so the request stays in-flight while the test fires a cancellation."
  [^CountDownLatch release]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              bs (.getBytes "{\"ok\":true}" "UTF-8")]
                          (.await release 30 TimeUnit/SECONDS)
                          (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
                          (try
                            (.sendResponseHeaders ex 200 (long (count bs)))
                            (with-open [os (.getResponseBody ex)]
                              (.write os bs))
                            (catch Throwable _ nil))))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

;; a stand-in for an AbortController.signal handle — any non-nil value.
;; On JVM `:abort-signal` is a no-op (CLJS-only); these tests only need it
;; to be PRESENT + non-nil so the dual-source shape is exercised.
(def ^:private signal-stub (Object.))
(def ^:private base-request {:method :get :url "http://localhost/x"})

;; ---- (1) both-supplied is LEGAL — must NOT throw ---------------------------

(deftest both-abort-signal-and-request-id-accepted
  (testing "rf2-q8vbna — supplying BOTH a non-nil :abort-signal AND a
            non-nil :request-id is a legal configuration and MUST NOT throw
            :rf.error/http-bad-abort-config (the removed guard)"
    (let [ex (call-managed! {:request      base-request
                             :request-id   :article/load
                             :abort-signal signal-stub})]
      (is (not (bad-abort-config-throw? ex))
          "dual-source config must NOT be rejected at the dispatch site")
      (is (nil? ex)
          "the call dispatches without throwing at all"))))

(deftest both-with-vector-request-id-accepted
  (testing "rf2-q8vbna — accepted regardless of the :request-id value shape
            (a compound vector id here)"
    (let [ex (call-managed! {:request      base-request
                             :request-id   [:articles :load "hello"]
                             :abort-signal signal-stub})]
      (is (not (bad-abort-config-throw? ex)))
      (is (nil? ex)))))

;; ---- (2a) finalise order: user-abort-first => one :reason :user reply ------
;;
;; The external `:abort-signal` funnels into the same internal controller a
;; manual `:rf.http/managed-abort` on the request's `:request-id` drives. On
;; the JVM the external signal is a no-op, so we exercise the user-abort
;; finalise path through `:rf.http/managed-abort` while the request ALSO
;; carries `:abort-signal` — the dual-source shape. The once-only CAS must
;; deliver EXACTLY ONE :rf.http/aborted :reason :user reply.

(deftest dual-source-user-abort-first-yields-single-user-reply
  (testing "rf2-q8vbna — with BOTH keys present, a user abort on the
            :request-id finalises as exactly one :rf.http/aborted :reason
            :user reply (the :finalised? CAS pins single-dispatch)"
    (let [release (CountDownLatch. 1)
          srv     (start-blocking-server! release)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request      {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :request-id   :dual
                    :abort-signal signal-stub      ; BOTH keys — was rejected
                    :decode       :json
                    :on-failure   [:reply/recorder]
                    :on-success   [:reply/recorder]}]]}))
        (rf/reg-event :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :dual]]}))

        (rf/dispatch-sync [:issue])
        (await-condition! #(seq (http-managed/in-flight-snapshot)) 2000)
        ;; Fire the user abort while the request is in-flight (server blocked).
        (rf/dispatch-sync [:do/abort])
        (await-condition! #(seq @replies))
        ;; Quiescence — prove no SECOND outcome arrives after the server is
        ;; released (the natural-completion path must lose the CAS / be
        ;; reclassified away, never dispatching a second reply).
        (.countDown release)
        (Thread/sleep 150)

        (let [reply (first @replies)]
          (is (= :cancelled (:status reply)))
          (is (= :rf.http/aborted (get-in reply [:error :kind]))
              "user-abort-first finalises as :rf.http/aborted")
          (is (= :user (get-in reply [:error :reason]))
              "the user-abort source determines :reason :user"))
        (is (= 1 (count @replies))
            "AT MOST ONE terminal outcome — the once-only :finalised? CAS")
        (is (empty? (http-managed/in-flight-snapshot))
            "in-flight registry is clean after the single aborted reply")
        (finally
          (.countDown release)
          (stop-server! srv))))))

;; ---- (2b) finalise order: supersede-first => suppressed + stale trace ------
;;
;; A fresh request with the SAME :request-id supersedes the prior one while
;; the prior still carries BOTH keys. The superseded attempt's reply target
;; MUST NOT run; a :rf.http/stale-suppressed trace records the supersession.
;; The superseding attempt yields its own single outcome. No second reply
;; lands for the superseded work — the once-only CAS holds across both keys.

(deftest dual-source-supersede-first-suppresses-and-traces
  (testing "rf2-q8vbna — with BOTH keys present on the prior request, a
            same-:request-id supersede suppresses the prior reply (no app
            target) + emits a :rf.http/stale-suppressed trace; the
            superseding request yields exactly one outcome"
    (let [release      (CountDownLatch. 1)
          srv          (start-blocking-server! release)
          prior-fired? (atom false)
          fresh-ok?    (atom false)
          stale-traces (atom [])
          cb-id        ::q8vbna-stale]
      (try
        (trace/register-listener! cb-id
          (fn [ev]
            (when (= :rf.http/stale-suppressed (:operation ev))
              (swap! stale-traces conj ev))))
        (rf/reg-event :search/prior
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request      {:url (str "http://127.0.0.1:" (:port srv) "/?stale")}
                    :request-id   :dual
                    :abort-signal signal-stub      ; BOTH keys on the prior
                    :decode       :json
                    :on-success   [:search/prior-reply]
                    :on-failure   [:search/prior-reply]}]]}))
        (rf/reg-event :search/fresh
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request      {:url (str "http://127.0.0.1:" (:port srv) "/?fresh")}
                    :request-id   :dual
                    :abort-signal signal-stub      ; BOTH keys on the fresh one too
                    :decode       :json
                    :on-success   [:search/fresh-ok]
                    :on-failure   [:search/fresh-ok]}]]}))
        (rf/reg-event :search/prior-reply (fn [_ _] (reset! prior-fired? true) {}))
        (rf/reg-event :search/fresh-ok    (fn [_ _] (reset! fresh-ok?    true) {}))

        (rf/dispatch-sync [:search/prior])
        (await-condition! #(seq (http-managed/in-flight-snapshot)) 2000)
        ;; Supersede with the same :request-id BEFORE the server answers.
        (rf/dispatch-sync [:search/fresh])
        ;; Release the server so the fresh request can complete.
        (.countDown release)
        ;; Wait for the fresh request's reply + the stale trace.
        (await-condition! #(true? @fresh-ok?))
        (await-condition! #(seq @stale-traces) 2000)
        ;; Quiescence — prove the superseded attempt never dispatches a reply.
        (Thread/sleep 150)

        (is (false? @prior-fired?)
            "the superseded request's reply target MUST NOT run (supersede-first wins)")
        (is (true? @fresh-ok?)
            "the superseding request yields its own single outcome")
        (let [ev   (first @stale-traces)
              tags (:tags ev)]
          (is (= :rf.http/stale-suppressed (:operation ev))
              "supersede emits a :rf.http/stale-suppressed trace")
          (is (= :suppressed (:rf.reply/work-status tags))
              "the suppressed work-status rides the stale trace")
          (is (= :rf.http/request-id-superseded (:rf.reply/stale-reason tags))
              ":stale-reason names the supersession"))
        (finally
          (trace/unregister-listener! cb-id)
          (.countDown release)
          (stop-server! srv))))))
