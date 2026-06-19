(ns re-frame.http-managed-test
  "JVM smoke tests for Spec 014 — `:rf.http/managed`.

  Covers the canned-stub fxs (no real network IO needed for the stubs)
  AND, where in-process HTTP is convenient, the real
  `java.net.http.HttpClient`-backed transport against a tiny
  com.sun.net.httpserver test server.

  Per Spec 014 §Implementation status — JVM transport is part of the
  CLJS reference implementation's claim."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.json :as util-json]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.http.decode :as http-decode]
            [re-frame.http.encoding :as http-encoding]
            [re-frame.http.managed :as http-managed]
            [re-frame.late-bind :as late-bind]
            ;; rf2-cdmle — the canned-stub fxs no longer register at
            ;; `re-frame.http.managed` load time. This test file uses
            ;; `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}`
            ;; throughout, so it opts in by requiring the test-support ns.
            ;; Loading registers `:rf.http/managed-canned-success` and
            ;; `:rf.http/managed-canned-failure` against the same handler
            ;; bodies the earlier `(when interop/debug-enabled? ...)` gate
            ;; wired up.
            [re-frame.http.test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP / machine / routing fxs now require a carried
  ;; frame stamp. This suite exercises the ambient dispatch path against
  ;; a single conventional app frame, so register `:rf/default` explicitly
  ;; and pin it as the established scope for the whole body via with-frame.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http.managed :reload)
  ;; rf2-cdmle — clear-all! above wipes the canned-stub fx registrations
  ;; that re-frame.http.test-support put into the registrar at first
  ;; load. Reload the test-support ns so its registration body fires
  ;; again for the next test (mirrors the http-managed reload above).
  (require 're-frame.http.test-support :reload)
  ((requiring-resolve 're-frame.machines/reset-timers!))
  (http-managed/clear-all-in-flight!)
  ;; rf2-r5m22 — the per-frame HTTP interceptor chain lives in a separate
  ;; registry (internal to `re-frame.http.middleware`; observe via
  ;; `http-managed/interceptors-snapshot`), NOT the registrar `clear-all!`
  ;; wipes above. Tests that register `:before` / `:after` interceptors
  ;; (the canned-path `:after` coverage) must clear it between tests, or
  ;; a leaked `:after` mutates every subsequent test's reply payload.
  (http-managed/clear-all-http-interceptors!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- a tiny in-process HTTP server ----------------------------------------
;;
;; com.sun.net.httpserver ships with the JDK; spinning up one per test
;; is fast enough (~5ms) and gives us a real socket to point HttpClient at.

(defn- start-server!
  "Start an HttpServer with the given handler. Returns a {:server ::server :port N} map.
  Stop with (.stop server 0)."
  [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx
                 (reify HttpHandler
                   (handle [_ exchange]
                     (handler exchange))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}]
  (.stop server 0))

(defn- write-response! [^HttpExchange exchange status content-type body]
  (let [bytes (.getBytes (str body) "UTF-8")]
    (when content-type
      (-> exchange .getResponseHeaders (.set "Content-Type" content-type)))
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

;; ---- helpers --------------------------------------------------------------

(defn- await-reply!
  "Wait up to `timeout-ms` for `(pred db)` to be truthy against
  `(rf/app-db-value :rf/default)`. Returns the final db on success;
  throws `ex-info` carrying `:rf.error/id`
  `:rf.error/poll-until-timeout` on timeout. Thin alias over
  `test-support/poll-until` (rf2-fun38) — preserves the per-file
  `db`-closing-arity shape that read sites here expect."
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until
     #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "http-managed reply"})))

;; ---- 1. canned-success: round-trip default reply addressing ---------------

(deftest canned-success-default-reply-addressing
  (testing "the canned-success stub dispatches a default reply (originating event-id with :rf/reply)"
    (rf/reg-event :article/load
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          (case (:kind reply)
            :success {:db (assoc-in db [:article :data] (:value reply))}
            :failure {:db (assoc-in db [:article :error] (:failure reply))})
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles/hello"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:article/load {:slug "hello"}]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    ;; Stubs synthesise replies via the router; await the dispatch.
    (let [db (await-reply! #(some? (get-in % [:article :data])))]
      (is (= {:stubbed true} (get-in db [:article :data]))))))

;; ---- 2. canned-failure: explicit on-failure addressing ---------------------

(deftest canned-failure-explicit-on-failure
  (testing "explicit :on-failure routes the failure reply to the named handler"
    (rf/reg-event :auth/login
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request   {:method :post :url "/auth/login"}
                :on-failure [:auth/login-error]}]]}))
    (rf/reg-event :auth/login-error
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :auth-error payload)}))
    (rf/dispatch-sync [:auth/login]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (await-reply! #(some? (:auth-error %)))]
      (is (= :failure (get-in db [:auth-error :kind])))
      (is (= :rf.http/transport (get-in db [:auth-error :failure :kind]))))))

;; ---- 3. silenced reply (on-success nil) -----------------------------------

(deftest silenced-reply-on-success-nil
  (testing "explicit :on-success nil swallows the reply silently"
    (let [seen (atom 0)]
      (rf/reg-event :ping
        (fn [_ _]
          (swap! seen inc)
          {:fx [[:rf.http/managed
                 {:request    {:url "/ping"}
                  :on-success nil}]]}))
      (rf/dispatch-sync [:ping]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
      ;; Timer-semantics sleep (rf2-fun38): asserting *absence* of a reply
      ;; re-dispatch (:on-success nil swallows it). No observable signal
      ;; to poll against — give the canned-success path a quiescence
      ;; window then assert @seen stayed at 1.
      (Thread/sleep 100)
      ;; Only the initial dispatch fired :ping; no reply.
      (is (= 1 @seen)))))

;; ---- 3b. :after-ms delay on the canned-stub fxs (rf2-j1mo4) ----------------
;;
;; Mike-ruled (B): a delay is a PARAMETER of the existing canned fx, not a
;; new `-later` fx id. Absent / 0 `:after-ms` = the immediate behaviour the
;; tests above already pin; a positive `:after-ms` defers the reply via the
;; framework-native `:dispatch-later` (observable in the tape, time-travel-
;; safe — NOT raw `set-timeout!`).

(defn- canned-success-reply-event
  "Register :j1mo4/load whose reply branch records the success value, and
  dispatch it through the canned-success stub with the given extra args
  merged onto the managed args-map. Returns immediately; callers poll
  `await-reply!` for the landed reply."
  [extra-args]
  (rf/reg-event :j1mo4/load
    (fn [{:keys [db]} [_ msg]]
      (if-let [reply (:rf/reply msg)]
        {:db (assoc-in db [:j1mo4 :value] (:value reply))}
        {:fx [[:rf.http/managed
               (merge {:request {:method :get :url "/j1mo4"}
                       :decode  :json}
                      extra-args)]]})))
  (rf/dispatch-sync [:j1mo4/load {}]
                    {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}}))

(deftest after-ms-absent-is-immediate
  (testing "no :after-ms — the canned-success reply lands immediately, exactly
            as the pre-rf2-j1mo4 behaviour (sync dispatch-sync drain delivers
            the reply with no timer tick)"
    (canned-success-reply-event {:value {:n 1}})
    ;; Immediate path runs inside the dispatch-sync drain — the reply is
    ;; already present without polling a timer.
    (is (= {:n 1} (get-in (rf/app-db-value :rf/default) [:j1mo4 :value]))
        "reply landed synchronously")))

(deftest after-ms-zero-is-immediate
  (testing ":after-ms 0 (and any non-positive value) is treated as immediate —
            absent/0 must preserve current behaviour"
    (canned-success-reply-event {:value {:n 2} :after-ms 0})
    (is (= {:n 2} (get-in (rf/app-db-value :rf/default) [:j1mo4 :value]))
        "reply landed synchronously with :after-ms 0")))

(deftest after-ms-positive-defers-via-dispatch-later
  (testing ":after-ms N defers the canned reply by one :dispatch-later tick —
            the reply is NOT present synchronously, lands after the timer, and
            the deferred dispatch is observable in the tape with
            :source :fx-dispatch-later (NOT raw set-timeout!)"
    (let [traces      (atom [])
          listener-id ::j1mo4-after-ms]
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        (canned-success-reply-event {:value {:n 3} :after-ms 30})
        ;; The reply must NOT be present synchronously — the dispatch-sync
        ;; drain only schedules the :dispatch-later; nothing has delivered yet.
        (is (nil? (get-in (rf/app-db-value :rf/default) [:j1mo4 :value]))
            "reply deferred — not present immediately after dispatch-sync")
        ;; After the timer tick the reply lands.
        (let [db (await-reply! #(some? (get-in % [:j1mo4 :value])))]
          (is (= {:n 3} (get-in db [:j1mo4 :value]))
              "deferred reply landed after the :dispatch-later tick"))
        ;; Tape observability: the deferred re-dispatch of the framework
        ;; deliverer event rode :dispatch-later, so its :rf.event/dispatched
        ;; trace carries :source :fx-dispatch-later. This is the load-bearing
        ;; "observable in the tape, not raw set-timeout!" assertion.
        (let [later (filter #(and (= :rf.event/dispatched (:operation %))
                                  (= :fx-dispatch-later (:source %)))
                            @traces)]
          (is (seq later)
              "a :dispatch-later-sourced dispatch appears in the tape")
          (is (some #(= :rf.http/deliver-canned-reply
                        (first (:rf.event/v (:tags %))))
                    later)
              "the deferred dispatch is the framework canned-reply deliverer"))
        (finally
          (trace/unregister-listener! listener-id))))))

(deftest after-ms-positive-on-failure-defers
  (testing ":after-ms N also defers the canned-FAILURE reply by a
            :dispatch-later tick (symmetric with the success path)"
    (rf/reg-event :j1mo4/fail-delayed
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request    {:method :get :url "/j1mo4/fail"}
                :after-ms   30
                :on-failure [:j1mo4/failed]}]]}))
    (rf/reg-event :j1mo4/failed
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :j1mo4-error payload)}))
    (rf/dispatch-sync [:j1mo4/fail-delayed]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    ;; Deferred — not present synchronously after the dispatch-sync drain.
    (is (nil? (:j1mo4-error (rf/app-db-value :rf/default)))
        "failure reply deferred — not present immediately")
    (let [db (await-reply! #(some? (:j1mo4-error %)))]
      (is (= :rf.http/transport (get-in db [:j1mo4-error :failure :kind]))
          "deferred failure reply landed after the :dispatch-later tick"))))

;; ---- 3c. canned-stub path runs the :after interceptor chain (rf2-r5m22) ----
;;
;; The per-frame HTTP interceptor chain has two halves: :before (request-
;; side) and :after (response-side, Spec 014 §Middleware). The real
;; transport path fires both (managed-handler runs :before;
;; http_transport/dispatch-reply! runs :after). Before rf2-r5m22 the
;; canned-stub path ran ONLY :before, so a test using the
;; :rf.http/managed-canned-* fxs with an :after interceptor (response-time
;; telemetry, header-driven auth refresh — the exact use-cases the
;; middleware contract sells) silently skipped that :after, diverging the
;; stub path from production. These tests pin that the canned path now
;; threads run-after-chain! before dispatching, mirroring the real path.

(deftest canned-success-runs-after-interceptor-chain
  (testing "rf2-r5m22 — the canned-success stub path fires a registered
            :after interceptor (was previously skipped — only :before ran
            on the canned path) and its response transform reaches the
            :on-success reply, mirroring the real-transport path"
    (let [order (atom [])]
      (rf/reg-http-interceptor :r5m22/touch
        {:before (fn [ctx] (swap! order conj :before) ctx)
         :after  (fn [_ctx resp]
                   (swap! order conj :after)
                   (update resp :value assoc :touched-by :after))})
      (rf/reg-event :r5m22/load
        (fn [{:keys [db]} [_ msg]]
          (if-let [reply (:rf/reply msg)]
            {:db (assoc db :reply reply)}
            {:fx [[:rf.http/managed
                   {:request {:method :get :url "/r5m22"}
                    :value   {:ok true}}]]})))
      (rf/dispatch-sync [:r5m22/load {}]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
      (let [db (await-reply! #(some? (:reply %)))]
        (is (= [:before :after] @order)
            "BOTH halves of the chain fired on the canned path (was [:before] only)")
        (is (= :success (get-in db [:reply :kind])))
        (is (= :after (get-in db [:reply :value :touched-by]))
            ":after's transform of the reply-payload reached the :on-success target")
        (is (true? (get-in db [:reply :value :ok]))
            "the canned :value rode through the :after transform unscathed")))))

(deftest canned-success-after-sees-the-before-ctx
  (testing "rf2-r5m22 — the canned path's :after receives the SAME
            middleware-ctx the :before produced (request-correlation), just
            like the real-transport path. A :before that stashes a ctx key
            and an :after that reads it back proves the ctx threads through."
    (let [observed (atom nil)]
      (rf/reg-http-interceptor :r5m22/correlate
        {:before (fn [ctx] (assoc ctx ::marker :stashed-by-before))
         :after  (fn [ctx resp]
                   (reset! observed (::marker ctx))
                   resp)})
      (rf/reg-event :r5m22/load-corr
        (fn [{:keys [db]} [_ msg]]
          (if-let [reply (:rf/reply msg)]
            {:db (assoc db :reply reply)}
            {:fx [[:rf.http/managed
                   {:request {:method :get :url "/r5m22/corr"}
                    :value   {:ok true}}]]})))
      (rf/dispatch-sync [:r5m22/load-corr {}]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
      (await-reply! #(some? (:reply %)))
      (is (= :stashed-by-before @observed)
          ":after read the :before's stashed ctx key — same ctx threads through the canned path"))))

(deftest canned-failure-runs-after-interceptor-chain
  (testing "rf2-r5m22 — the canned-FAILURE stub path also fires the :after
            chain (symmetric with the success path); an :after can inspect
            the failure shape and tag the reply, mirroring the real-path
            401-auth-refresh use-case."
    (let [fired (atom false)]
      (rf/reg-http-interceptor :r5m22/fail-after
        {:after (fn [_ctx resp]
                  (reset! fired true)
                  (if (= :failure (:kind resp))
                    (assoc resp :tagged-by-after true)
                    resp))})
      (rf/reg-event :r5m22/fail
        (fn [{:keys [db]} [_ msg]]
          (if-let [reply (:rf/reply msg)]
            {:db (assoc db :reply reply)}
            {:fx [[:rf.http/managed
                   {:request {:method :get :url "/r5m22/fail"}}]]})))
      (rf/dispatch-sync [:r5m22/fail {}]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
      (let [db (await-reply! #(some? (:reply %)))]
        (is (true? @fired)
            ":after fired on the canned-failure path")
        (is (= :failure (get-in db [:reply :kind])))
        (is (true? (get-in db [:reply :tagged-by-after]))
            ":after's failure-shape transform reached the :on-failure reply")))))

;; ---- 4. real JVM transport: GET success -----------------------------------

(deftest jvm-real-get-success
  (testing "java.net.http.HttpClient transport — GET, JSON decode, default reply"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json"
                               "{\"article\":{\"title\":\"hello\",\"id\":42}}")))]
      (try
        (rf/reg-event :article/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              (case (:kind reply)
                :success {:db (assoc db :article (:value reply))}
                :failure {:db (assoc db :error  (:failure reply))})
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/articles/hello")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:article/load {}])
        (let [db (await-reply! #(some? (:article %)) 5000)]
          (is (= "hello" (get-in db [:article :article :title]))))
        (finally (stop-server! srv))))))

;; ---- 5. real JVM transport: 4xx routes through failure --------------------

(deftest jvm-real-http-4xx
  (testing "non-2xx 4xx response classifies as :rf.http/http-4xx"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 404 "application/json" "{\"error\":\"not-found\"}")))]
      (try
        (rf/reg-event :article/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/missing")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:article/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/http-4xx (get-in db [:reply :failure :kind])))
          (is (= 404 (get-in db [:reply :failure :status]))))
        (finally (stop-server! srv))))))

;; ---- 5b. HTML 404 with :decode :json — status check precedes decode ------
;;
;; Regression guard for rf2-lokk: a 4xx response whose body is HTML (or any
;; shape that would FAIL :json decode) MUST classify as :rf.http/http-4xx,
;; NOT :rf.http/decode-failure. Per Spec 014 §Failure categories, status
;; classification runs BEFORE decode — decode never fires on a non-2xx
;; response. The :body tag carries the raw response body-text.

(deftest jvm-html-404-with-json-decode-routes-to-http-4xx
  (testing "HTML 4xx response with :decode :json classifies as :rf.http/http-4xx (not :rf.http/decode-failure)"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 404 "text/html"
                               "<!doctype html><html><body><h1>Not Found</h1></body></html>")))]
      (try
        (rf/reg-event :page/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/missing")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:page/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/http-4xx (:kind failure))
              "status classification precedes decode: HTML body must NOT trigger :rf.http/decode-failure")
          (is (= 404 (:status failure)))
          ;; The :body is the RAW response body, not a decoded value.
          (is (string? (:body failure))
              ":body carries the raw response text on 4xx (decode skipped)")
          (is (clojure.string/includes? (:body failure) "Not Found")))
        (finally (stop-server! srv))))))

;; ---- 5c. throwing decoder on 200 still routes to :rf.http/decode-failure -
;;
;; The complement of 5b: a 2xx response whose decode pipeline throws DOES land
;; as :rf.http/decode-failure, since decode runs on success-eligible responses.
;; We use a custom decoder fn that throws — the JVM's :json fallback parser is
;; lenient (returns the raw string when malformed) so a thrown decoder is the
;; portable way to exercise the decode-failure path.

(deftest jvm-throwing-decoder-on-200-routes-to-decode-failure
  (testing "200 response whose decode pipeline throws classifies as :rf.http/decode-failure"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :page/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/ok")}
                      :decode  (fn [_text _headers]
                                 (throw (ex-info "boom" {})))}]]})))
        (rf/dispatch-sync [:page/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/decode-failure (:kind failure))
              "decode runs on 2xx; a thrown decoder surfaces as :rf.http/decode-failure"))
        (finally (stop-server! srv))))))

;; ---- 5b'. empty 2xx JSON body → success-nil (rf2-upexd.1, JVM full-stack) ---
;;
;; The cross-host parity contract (oyw04) for an empty/whitespace-only 2xx
;; JSON body is asserted host-symmetrically at the decode altitude in
;; `http_empty_body_parity_cljs_test.cljc` (runs on BOTH the JVM and CLJS
;; runners). This JVM full-stack test pins the SAME outcome end-to-end
;; through the real `java.net.http.HttpClient` transport + the
;; `handle-response!` → `run-accept` → `finalise-success!` cascade: an empty
;; 200 body with `Content-Type: application/json` must reply
;; `{:kind :success :value nil}`, NOT `{:kind :failure ...decode-failure}`.

(deftest jvm-empty-200-json-body-replies-success-nil
  (testing "rf2-upexd.1 — a 200 with an EMPTY body + application/json
            Content-Type (the common empty-success-envelope from a
            PUT/DELETE/POST-with-no-content) replies :success with :value
            nil through the full JVM transport cascade, NOT a
            :rf.http/decode-failure."
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "")))]
      (try
        (rf/reg-event :empty/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/empty")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:empty/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :success (get-in db [:reply :kind]))
              "an empty 2xx JSON body is a NORMAL outcome, not a decode-failure")
          (is (nil? (get-in db [:reply :value]))
              "the decoded value of an empty JSON body is nil"))
        (finally (stop-server! srv))))))

;; ---- 5b''. non-retried terminal failure emits NO retry-attempt (rf2-upexd.3) -
;;
;; `maybe-retry!`'s terminal (non-retry) branch previously emitted a
;; `:rf.http/retry-attempt` info trace whenever `(> max-attempts 1)`, even
;; when the just-failed kind was NEVER retry-eligible and no retry ever
;; fired. The spec (§Retry × :on-failure semantics) ties retry-attempt to
;; "each intermediate attempt" — a phantom retry-attempt for a request that
;; was never retried pollutes the trace semantics pair tools / 10x panels
;; read. The guard now also requires `(or (> attempt 1) (contains? on-set
;; kind))`, so a non-retried, non-eligible terminal failure emits nothing.

(deftest jvm-non-retried-decode-failure-emits-no-retry-attempt
  (testing "rf2-upexd.3 — a NON-retry-eligible failure (a :rf.http/decode-
            failure under `:retry {:on #{:rf.http/http-5xx} :max-attempts
            3}`) on attempt 1 must emit ZERO :rf.http/retry-attempt traces:
            no retry happened and the kind was never in :on. Pre-fix the
            blanket `(> max-attempts 1)` guard fired a phantom retry-attempt."
    (let [traces      (atom [])
          listener-id ::upexd3-no-phantom
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; 200 OK, but the decoder throws → :rf.http/decode-failure
              ;; (NOT in the :on set, so non-retryable).
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :upexd3/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/x")}
                      ;; Throwing decoder → decode-failure on attempt 1.
                      :decode  (fn [_text _headers] (throw (ex-info "boom" {})))
                      :retry   {:on           #{:rf.http/http-5xx}
                                :max-attempts 3
                                :backoff      {:base-ms 5 :factor 1 :max-ms 10}}}]]})))
        (rf/dispatch-sync [:upexd3/load {}])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          ;; The reply is the terminal decode-failure.
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/decode-failure (get-in db [:reply :failure :kind])))
          ;; The load-bearing assertion: no phantom retry-attempt trace.
          (let [retry-traces (filter #(= :rf.http/retry-attempt (:operation %))
                                     @traces)]
            (is (empty? retry-traces)
                (str "a non-retried, non-eligible terminal failure must emit no "
                     ":rf.http/retry-attempt trace; saw "
                     (count retry-traces) " — "
                     (pr-str (mapv #(get-in % [:tags :failure :kind]) retry-traces))))))
        (finally
          (trace/unregister-listener! listener-id)
          (stop-server! srv))))))

(deftest jvm-retry-eligible-exhaustion-still-emits-retry-attempts
  (testing "rf2-upexd.3 — counter-case: a RETRY-ELIGIBLE failure exhausting
            its attempts STILL emits the retry-attempt traces (the tighten
            is surgical — it removes only the phantom case). A 5xx under
            `:retry {:on #{:rf.http/http-5xx} :max-attempts 3}` retries
            twice and exhausts on attempt 3; the trace stream must carry the
            per-attempt retry-attempt events (the final one with
            :next-backoff-ms nil)."
    (let [traces      (atom [])
          listener-id ::upexd3-eligible
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 500 "application/json" "{\"err\":true}")))]
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :upexd3b/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/5xx")}
                      :decode  :json
                      :retry   {:on           #{:rf.http/http-5xx}
                                :max-attempts 3
                                :backoff      {:base-ms 5 :factor 1 :max-ms 10}}}]]})))
        (rf/dispatch-sync [:upexd3b/load {}])
        (let [db (await-reply! #(some? (:reply %)) 8000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/http-5xx (get-in db [:reply :failure :kind])))
          (let [retry-traces (filter #(= :rf.http/retry-attempt (:operation %))
                                     @traces)]
            (is (seq retry-traces)
                "a retry-eligible exhaustion MUST still emit retry-attempt traces")
            ;; The terminal exhaustion trace carries :next-backoff-ms nil.
            (is (some #(nil? (get-in % [:tags :next-backoff-ms])) retry-traces)
                "the final exhaustion retry-attempt carries :next-backoff-ms nil")))
        (finally
          (trace/unregister-listener! listener-id)
          (stop-server! srv))))))

;; ---- 5d. Content-Type lookup is case-insensitive (rf2-6hbo8) -------------
;;
;; Per Spec 014 §Request envelope, HTTP header names are case-insensitive.
;; The old `decode-response-body` only checked the two literal spellings
;; "content-type" and "Content-Type"; any other casing (e.g.
;; "CONTENT-TYPE", "Content-type") returned nil and `sniff-decoder` fell
;; through to :blob — JSON arriving as raw text.
;;
;; The CLJS Fetch path normalises (lower-case in `fetch-headers->map`) so
;; the bug only manifested when a hand-constructed headers map reached
;; `decode-response-body`. The fix is two-layered: the JVM transport
;; (`jvm-headers->map`) lower-cases at the boundary so the JVM path matches
;; the Fetch path, AND `http-decode/content-type-of` performs a
;; case-insensitive scan so any future code path that synthesises a
;; headers map (interceptors, middleware, tests) decodes correctly.
;;
;; These unit tests exercise the helper and `decode-response-body`
;; directly with mixed-case headers — they fail deterministically against
;; the pre-fix code regardless of transport. (A full JVM transport e2e
;; test would pass vacuously because `java.net.http.HttpHeaders.map()`
;; already returns lower-case keys.)

(deftest content-type-of-case-insensitive
  (testing "lowercase key"
    (is (= "application/json"
           (http-decode/content-type-of {"content-type" "application/json"}))))
  (testing "canonical Title-Case key"
    (is (= "application/json"
           (http-decode/content-type-of {"Content-Type" "application/json"}))))
  (testing "all-caps key (the original bug — CONTENT-TYPE)"
    (is (= "application/json"
           (http-decode/content-type-of {"CONTENT-TYPE" "application/json"}))))
  (testing "mixed casing"
    (is (= "application/json"
           (http-decode/content-type-of {"Content-type" "application/json"})))
    (is (= "text/plain"
           (http-decode/content-type-of {"cOnTeNt-TyPe" "text/plain"}))))
  (testing "keyword key (some middlewares use keywords)"
    (is (= "application/json"
           (http-decode/content-type-of {:content-type "application/json"})))
    (is (= "application/json"
           (http-decode/content-type-of {:Content-Type "application/json"}))))
  (testing "absent / unrelated headers"
    (is (nil? (http-decode/content-type-of {})))
    (is (nil? (http-decode/content-type-of {"X-Foo" "bar"})))
    (is (nil? (http-decode/content-type-of nil)))
    (is (nil? (http-decode/content-type-of "not-a-map")))))

(deftest decode-response-body-resolves-content-type-case-insensitively
  (testing "JSON decode under :auto fires when Content-Type has non-canonical casing"
    ;; This is the original bug: the response headers carry "CONTENT-TYPE"
    ;; (or any non-canonical casing); the pre-fix code's two-spelling `get`
    ;; returned nil; `sniff-decoder` fell through to :blob; the caller
    ;; received the raw body string. Post-fix, the helper resolves the
    ;; header regardless of casing and JSON decodes correctly.
    (doseq [ct-key ["CONTENT-TYPE" "Content-type" "content-type" "Content-Type" "cOnTeNt-TyPe"]]
      (testing (str "casing: " ct-key)
        (let [decoded (http-decode/decode-response-body
                        {:body-text        "{\"ok\":true}"
                         :headers          {ct-key "application/json"}
                         :decode           :auto})]
          (is (= {:ok true} decoded)
              (str "non-canonical Content-Type casing " (pr-str ct-key)
                   " must sniff to :json, not :blob")))))))

;; ---- 5e. binary decode reads the native body, not body-text (rf2-5zj6t) ---
;;
;; Spec 014 §Decoding lists `:blob` / `:array-buffer` / `:form-data` as
;; distinct binary decode shapes. The CLJS transport used to always
;; pre-read the Fetch response via `(.text resp)`, so the binary decode
;; branches resolved to the body-TEXT string — a caller asking
;; `:decode :blob` for an image got a lossy UTF-8 string. The fix routes
;; the resolved decode mode into the transport, which now picks the
;; correct Fetch reader (`.blob()` / `.arrayBuffer()` / `.formData()`)
;; and rides the native body under `:body-binary`; `decode-response-body`
;; returns it verbatim for the binary branches.
;;
;; `binary-read-kind` is the pure resolution helper the transport calls
;; BEFORE consuming the body (a Fetch Response body may be read once).
;; These JVM unit tests exercise it and the `decode-response-body`
;; binary-return path directly with stand-in binary values — they fail
;; deterministically against the pre-fix code (which returned the text
;; string for every binary mode).

(deftest binary-read-kind-resolves-binary-decode-modes
  (testing "explicit binary modes resolve to themselves"
    (is (= :blob         (http-decode/binary-read-kind :blob {})))
    (is (= :array-buffer (http-decode/binary-read-kind :array-buffer {})))
    (is (= :form-data    (http-decode/binary-read-kind :form-data {}))))
  (testing "text-based / structured modes resolve to nil (read .text)"
    (is (nil? (http-decode/binary-read-kind :json {})))
    (is (nil? (http-decode/binary-read-kind :text {})))
    (is (nil? (http-decode/binary-read-kind (fn [_ _] :decoded) {})))
    (is (nil? (http-decode/binary-read-kind [:map] {}))
        "a Malli schema is a text (JSON-parse) mode, not binary"))
  (testing ":auto sniffs the Content-Type — binary type → :blob (rf2-5zj6t)"
    (is (= :blob (http-decode/binary-read-kind :auto {"content-type" "image/png"}))
        ":auto over a binary Content-Type reads as a Blob, not lossy text")
    (is (= :blob (http-decode/binary-read-kind nil {"content-type" "application/octet-stream"}))
        "omitted :decode (== :auto) over a binary Content-Type also reads binary"))
  (testing ":auto sniffs the Content-Type — text/JSON → nil (read .text)"
    (is (nil? (http-decode/binary-read-kind :auto {"content-type" "application/json"})))
    (is (nil? (http-decode/binary-read-kind :auto {"content-type" "text/plain"})))
    (is (nil? (http-decode/binary-read-kind nil  {"content-type" "text/html"})))))

(deftest decode-response-body-returns-native-binary-for-binary-modes
  (testing "binary decode modes return the pre-read :body-binary verbatim"
    ;; Stand-in for the native Blob / ArrayBuffer / FormData the CLJS
    ;; transport reads. The decode pipeline must return THIS value, not
    ;; the body-text string (the pre-fix bug).
    (let [native (Object.)]
      (doseq [mode [:blob :array-buffer :form-data]]
        (testing (str "mode: " mode)
          (is (identical? native
                          (http-decode/decode-response-body
                            {:body-text   "lossy-utf8-text"
                             :body-binary native
                             :headers     {}
                             :decode      mode}))
              (str mode " must return the native binary body, not body-text"))))))
  (testing "binary mode with no :body-binary (e.g. JVM transport) falls back to body-text"
    (doseq [mode [:blob :array-buffer :form-data]]
      (is (= "raw-payload"
             (http-decode/decode-response-body
               {:body-text        "raw-payload"
                :headers          {}
                :decode           mode}))
          (str mode " with absent :body-binary returns the raw body-text payload")))))

;; ---- 6. retry exhaustion --------------------------------------------------

(deftest jvm-retry-exhaustion
  (testing ":retry exhausts after :max-attempts, dispatching a single :on-failure"
    (let [hits (atom 0)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (swap! hits inc)
              (write-response! ex 500 "application/json" "{\"err\":true}")))]
      (try
        (rf/reg-event :flaky/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/flaky")}
                      :decode  :json
                      :retry   {:on           #{:rf.http/http-5xx}
                                :max-attempts 3
                                :backoff      {:base-ms 5 :factor 1 :max-ms 10}}}]]})))
        (rf/dispatch-sync [:flaky/load])
        (let [db (await-reply! #(some? (:reply %)) 8000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/http-5xx (get-in db [:reply :failure :kind])))
          ;; The server saw all 3 attempts.
          (is (= 3 @hits)))
        (finally (stop-server! srv))))))

;; ---- 7. retry recover -----------------------------------------------------

(deftest jvm-retry-recover
  (testing ":retry recovers when an intermediate attempt succeeds"
    (let [hits (atom 0)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (let [n (swap! hits inc)]
                (if (= 1 n)
                  (write-response! ex 500 "application/json" "{\"err\":true}")
                  (write-response! ex 200 "application/json" "{\"ok\":true}")))))]
      (try
        (rf/reg-event :recover/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/recover")}
                      :decode  :json
                      :retry   {:on           #{:rf.http/http-5xx}
                                :max-attempts 3
                                :backoff      {:base-ms 5 :factor 1 :max-ms 10}}}]]})))
        (rf/dispatch-sync [:recover/load])
        (let [db (await-reply! #(some? (:reply %)) 8000)]
          (is (= :success (get-in db [:reply :kind])))
          (is (= {:ok true} (get-in db [:reply :value])))
          (is (= 2 @hits)))
        (finally (stop-server! srv))))))

;; ---- 8. transport failure --------------------------------------------------

(deftest jvm-transport-failure
  (testing "connection-refused classifies as :rf.http/transport"
    (rf/reg-event :load
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :reply reply)}
          {:fx [[:rf.http/managed
                 ;; Pick a port we expect to be closed.
                 {:request {:url "http://127.0.0.1:1/never"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:load])
    (let [db (await-reply! #(some? (:reply %)) 5000)]
      (is (= :failure (get-in db [:reply :kind])))
      (is (= :rf.http/transport (get-in db [:reply :failure :kind]))))))

;; ---- 8b. abort on unknown request-id is a silent no-op (rf2-kdwnq) -------
;;
;; Per http_handlers.cljc:113-125, `managed-abort-handler` resolves the
;; abort-fn through the in-flight registry and fires it; a missing
;; entry yields `nil` from `lookup-in-flight`, the `when-let` collapses,
;; and the handler returns `nil` without dispatch / throw. The shape is
;; correct (idempotent abort) but not asserted — a regression that
;; throws here (e.g. someone changing `when-let` to `let`, or adding a
;; precondition) would only surface as flake in apps that race
;; abort-then-cleanup. Pin the no-op contract.

(deftest jvm-managed-abort-unknown-request-id-is-silent-noop
  (testing ":rf.http/managed-abort on a request-id never seen by the in-flight
            registry completes without throwing and dispatches no reply
            (no :on-failure, no trace error). Idempotent abort contract."
    (let [reply-fired? (atom false)
          traces       (atom [])
          listener-id  ::kdwnq-trace]
      (try
        (trace/register-listener! listener-id
                                  (fn [ev] (swap! traces conj ev)))
        ;; If a reply ever dispatches the test will catch it (silently
        ;; ignored handler) — but the load-bearing assertion is that no
        ;; throw escapes the abort fx.
        (rf/reg-event :kdwnq/abort-never-issued
          (fn [_ _]
            {:fx [[:rf.http/managed-abort :kdwnq/never-issued]]}))
        (rf/reg-event :kdwnq/some-reply
          (fn [{:keys [db]} _]
            (reset! reply-fired? true)
            {:db db}))
        ;; The call itself must not throw.
        (is (nil? (rf/dispatch-sync [:kdwnq/abort-never-issued]))
            "dispatch returns nil; abort handler is a silent no-op on unknown id")
        ;; Idempotent — abort the same unknown id a second time.
        (is (nil? (rf/dispatch-sync [:kdwnq/abort-never-issued]))
            "second abort of the same unknown id is also a silent no-op")
        ;; Timer-semantics sleep (rf2-fun38): assertion is the *absence*
        ;; of any reply — there is no observable signal to poll against
        ;; (we are proving nothing fires). The 50ms window is the
        ;; quiescence budget; if a stray reply was going to come, it
        ;; would have surfaced within this slack.
        (Thread/sleep 50)
        (is (false? @reply-fired?)
            "no reply event was dispatched — the registry knew nothing about the id")
        (let [errors (filter #(= :error (:op-type %)) @traces)]
          (is (empty? errors)
              (str "no :rf.error/* trace fired for the abort no-op; saw: "
                   (mapv :operation errors))))
        (finally
          (trace/unregister-listener! listener-id))))))

;; ---- 9. abort by request-id -----------------------------------------------

(deftest jvm-abort-by-request-id
  (testing ":rf.http/managed-abort cancels an in-flight request by id"
    (let [latch (CountDownLatch. 1)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Block until the test releases the latch.
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"too\":\"late\"}")))]
      (try
        (rf/reg-event :slow/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                      :request-id :slow
                      :decode     :json}]]})))
        (rf/reg-event :slow/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :slow]]}))
        (rf/dispatch-sync [:slow/load])
        ;; Poll until the request is actually registered as in-flight —
        ;; aborting before the executor has stamped the handle is a no-op
        ;; (rf2-fun38 — replaces fixed Thread/sleep 50).
        (test-support/poll-until
          #(contains? (http-managed/in-flight-snapshot) :slow)
          {:label ":slow registered as in-flight before abort"})
        (rf/dispatch-sync [:slow/abort])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/aborted (get-in db [:reply :failure :kind]))))
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- 9a. rf2-on7sj — abort + slow server must dispatch EXACTLY ONE reply ----
;;
;; Pre-fix: the JVM abort-fn closure called finalise-failure! with the
;; synthesised :rf.http/aborted reply, but DID NOT `.cancel cf true` on
;; the underlying CompletableFuture. When the server eventually
;; responded (or the cf naturally completed), `.whenComplete` fired
;; handle-response! → finalise-success! (or maybe-retry!), dispatching
;; a SECOND reply for the same request — observable on the consuming
;; event handler as a double-reply on slow-server aborts.
;;
;; The existing `jvm-abort-by-request-id` test (above) doesn't notice:
;; it asserts the abort reply lands, then ends without waiting for the
;; latch release that would fire the second reply.
;;
;; This regression test:
;;   1. Spins up a latched server that blocks until released.
;;   2. Dispatches the managed request.
;;   3. Aborts (synthesised reply fires immediately).
;;   4. RELEASES the latch (lets the underlying transport finish).
;;   5. Waits long enough for the natural-completion path to fire.
;;   6. Asserts the reply-counter is EXACTLY 1.

(deftest jvm-abort-then-server-release-emits-exactly-one-reply-rf2-on7sj
  (testing "rf2-on7sj — slow-server abort must produce exactly ONE reply
            even after the underlying server eventually responds. The
            abort-fn cancels the CompletableFuture and CAS-guards the
            reply path so the latent whenComplete callback's natural
            second emit is suppressed."
    (let [latch (CountDownLatch. 1)
          reply-count (atom 0)
          all-replies (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Block until the test releases the latch — simulating
              ;; a slow server that responds AFTER abort.
              (.await latch 10 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json"
                               "{\"server\":\"responded-after-abort\"}")))]
      (try
        (rf/reg-event :on7sj/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              (do
                (swap! reply-count inc)
                (swap! all-replies conj reply)
                {:db (assoc db :reply reply)})
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                      :request-id :on7sj/req
                      :decode     :json}]]})))
        (rf/reg-event :on7sj/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :on7sj/req]]}))

        ;; Issue the request. Server blocks on the latch.
        (rf/dispatch-sync [:on7sj/load])
        ;; Poll until the request is registered in-flight before aborting
        ;; (rf2-fun38 — replaces fixed Thread/sleep 50).
        (test-support/poll-until
          #(contains? (http-managed/in-flight-snapshot) :on7sj/req)
          {:label ":on7sj/req registered as in-flight before abort"})
        ;; Abort while server is still blocked. The synthesised
        ;; :rf.http/aborted reply should fire immediately.
        (rf/dispatch-sync [:on7sj/abort])
        ;; Wait for the abort reply.
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/aborted (get-in db [:reply :failure :kind])))
          (is (= 1 @reply-count)
              "exactly one reply must have fired immediately after abort"))

        ;; Release the server. Pre-fix: the underlying CompletableFuture
        ;; was never cancelled and would now drain → whenComplete fires
        ;; → second reply dispatched (the load-bearing bug). Post-fix:
        ;; the cf.cancel + :finalised? CAS guard ensures the second
        ;; reply path no-ops.
        (.countDown latch)
        ;; Timer-semantics sleep (rf2-fun38): we are proving the *absence*
        ;; of a second reply — no observable signal to poll against.
        ;; 800ms is the quiescence budget; the JDK HttpClient executor
        ;; would surface any latent whenComplete callback well within
        ;; this window if the cf.cancel + CAS guard regressed.
        (Thread/sleep 800)

        (is (= 1 @reply-count)
            (str "rf2-on7sj — exactly ONE reply must fire across abort + server-release. "
                 "Pre-fix this would dispatch TWO. Saw "
                 @reply-count " replies: "
                 (pr-str (mapv :kind @all-replies))))
        (is (= 1 (count @all-replies))
            "the all-replies log carries a single entry, matching the counter")
        (is (= :rf.http/aborted (get-in (first @all-replies) [:failure :kind]))
            "the single reply is the abort reply, not the late natural-completion one")

        (finally (stop-server! srv))))))

;; ---- 10. thunk body -------------------------------------------------------

(deftest jvm-thunk-body
  (testing ":body as a thunk is invoked at request-send time"
    (let [thunk-calls (atom 0)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"echoed\":true}")))]
      (try
        (rf/reg-event :upload
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:method :post
                                :url    (str "http://127.0.0.1:" port "/upload")
                                :body   (fn []
                                          (swap! thunk-calls inc)
                                          {:payload :ok})
                                :request-content-type :json}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:upload])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :success (get-in db [:reply :kind])))
          (is (= 1 @thunk-calls)))
        (finally (stop-server! srv))))))

;; ---- 11. with-managed-request-stubs helper --------------------------------

(deftest with-managed-request-stubs-helper
  (testing "rf2-rzqan — with-managed-request-stubs routes :method+:url to the
            configured reply with NO per-call :fx-overrides (the documented
            wrapper contract: the helper installs the
            :rf.http/managed → :rf.http/managed-test-stub override for the
            body's dynamic extent, so plain dispatch-sync auto-routes)"
    (rf/reg-event :articles/list
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :result reply)}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs
      {[:get "/articles"] {:reply {:ok [:hello :world]}}}
      ;; NO manual :fx-overrides — this is the documented form. Pre-fix this
      ;; dispatch ran the real :rf.http/managed transport.
      (rf/dispatch-sync [:articles/list])
      (let [db (await-reply! #(some? (:result %)) 2000)]
        (is (= :success (get-in db [:result :kind])))
        (is (= [:hello :world] (get-in db [:result :value])))))))

;; ---- 11a. rf2-rzqan — bare wrapper INTERCEPTS, never reaching the real fx ---
;;
;; The load-bearing regression for rf2-rzqan: the documented
;; `with-managed-request-stubs` wrapper must route `:rf.http/managed`
;; through the route-map stub by ITSELF — the body must NOT need a manual
;; `:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}`. Pre-fix
;; the helper only registered the stub fx but did NOT install the override,
;; so a plain `dispatch-sync` inside the body ran the REAL production
;; transport (network IO / hang / nondeterminism / false-green).
;;
;; To prove the REAL fx is never reached we shadow `:rf.http/managed` with
;; a sentinel that flips an atom. If the override were absent the bare
;; dispatch would land on this sentinel (the real fx slot) and the atom
;; would flip; with the helper-installed override the dispatch routes to
;; the stub instead and the sentinel stays untouched while the stubbed
;; reply lands.

(deftest with-managed-request-stubs-intercepts-without-manual-override-rf2-rzqan
  (testing "rf2-rzqan — inside with-managed-request-stubs, a plain dispatch-sync
            (NO per-call :fx-overrides) is intercepted by the stub and the real
            :rf.http/managed fx slot is NEVER invoked"
    (let [real-fx-invoked? (atom false)]
      ;; Shadow the production fx slot with a sentinel. Reaching THIS proves
      ;; the override was absent (the pre-fix bug). The stub path bypasses it.
      (rf/reg-fx :rf.http/managed
                 (fn [_frame-ctx _args] (reset! real-fx-invoked? true) nil))
      (rf/reg-event :rzqan/load
        (fn [{:keys [db]} [_ msg]]
          (if-let [reply (:rf/reply msg)]
            {:db (assoc db :result reply)}
            {:fx [[:rf.http/managed
                   {:request {:method :get :url "/rzqan"}
                    :decode  :json}]]})))
      (rf/with-managed-request-stubs
        {[:get "/rzqan"] {:reply {:ok {:stubbed true}}}}
        ;; Bare wrapper form — the helper alone must route to the stub.
        (rf/dispatch-sync [:rzqan/load])
        (let [db (await-reply! #(some? (:result %)) 2000)]
          (is (= :success (get-in db [:result :kind]))
              "the stubbed reply landed via the route-map stub")
          (is (= {:stubbed true} (get-in db [:result :value]))
              "the configured :ok value rode through the synthesised success reply")
          (is (false? @real-fx-invoked?)
              "the real :rf.http/managed fx was NEVER invoked — the helper's
               installed override intercepted the dispatch (pre-fix: this fired
               the real transport)"))))))

(deftest with-managed-request-stubs-per-call-override-still-wins-rf2-rzqan
  (testing "rf2-rzqan — a per-call :fx-overrides inside the wrapper still wins
            over the helper-installed lexical default (precedence preserved:
            per-call > lexical > per-frame)"
    (let [chosen (atom nil)]
      ;; A deliberately-supplied per-call override target.
      (rf/reg-fx :rzqan/explicit-override
                 (fn [_frame-ctx _args] (reset! chosen :explicit) nil))
      (rf/reg-event :rzqan/load-explicit
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/rzqan-explicit"}
                  :decode  :json}]]}))
      (rf/with-managed-request-stubs
        {[:get "/rzqan-explicit"] {:reply {:ok {:via :stub}}}}
        ;; The body deliberately overrides :rf.http/managed itself; this must
        ;; beat the helper's lexical default and land on the explicit target.
        (rf/dispatch-sync [:rzqan/load-explicit]
                          {:fx-overrides {:rf.http/managed :rzqan/explicit-override}})
        (is (= :explicit @chosen)
            "the per-call :fx-overrides won over the helper's lexical default")))))

;; ---- 11a-azrcs. route-map stub keys off the POST-`:before` request --------
;;
;; rf2-azrcs (independent-review finding #2) — the route-map stub picked
;; `:method`/`:url` from the ORIGINAL pre-middleware args, then delegated to
;; the canned handler that ran the `:before` chain LATER. The real
;; `:rf.http/managed` handler runs `:before` FIRST, validates the FINAL url,
;; then sends the post-middleware request. So a base-URL / url-rewriting
;; `:before` made the stub key off the draft url:
;;   - false-fail when the route map is keyed to the FINAL url (what
;;     production sends) — the draft url misses it; and
;;   - false-green when keyed to the ORIGINAL url even though production
;;     issues a different one.
;; The fix runs the `:before` chain ONCE in the stub, keys the match against
;; the post-`:before` url, and emits via that same middleware-ctx (no
;; double-`:before`). A url-erasing `:before` now throws the production
;; `:rf.error/http-bad-request` instead of a synthetic stubbed reply.

(deftest stub-matches-post-before-url-rewrite-rf2-azrcs
  (testing "rf2-azrcs — a base-URL `:before` rewrites `/articles` → `/v2/articles`;
            the stub keyed to the FINAL `/v2/articles` matches (pre-fix it
            keyed off the draft `/articles` and fell through to the
            no-stub-matched failure)"
    (rf/reg-http-interceptor :azrcs/base-url
      {:before (fn [ctx]
                 (update-in ctx [:request :url]
                            (fn [u] (clojure.string/replace u #"^/" "/v2/"))))})
    (rf/reg-event :azrcs/list
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :result reply)}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs
      ;; Keyed to the FINAL url the `:before` produces — NOT the draft.
      {[:get "/v2/articles"] {:reply {:ok [:rewritten :ok]}}}
      (rf/dispatch-sync [:azrcs/list])
      (let [db (await-reply! #(some? (:result %)) 2000)]
        (is (= :success (get-in db [:result :kind]))
            "the stub matched the post-`:before` url")
        (is (= [:rewritten :ok] (get-in db [:result :value]))
            "the configured :ok value for the FINAL url rode through")))))

(deftest stub-does-not-match-stale-original-url-rf2-azrcs
  (testing "rf2-azrcs (complement) — a route map keyed to the ORIGINAL
            (draft) url no longer false-greens: with a url-rewriting
            `:before`, the post-`:before` url is what the stub matches, so
            the stale-key entry misses and the no-stub-matched failure fires"
    (rf/reg-http-interceptor :azrcs/base-url2
      {:before (fn [ctx]
                 (update-in ctx [:request :url]
                            (fn [u] (clojure.string/replace u #"^/" "/v2/"))))})
    (rf/reg-event :azrcs/list2
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :result reply)}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs
      ;; Keyed to the ORIGINAL draft url — production would issue /v2/articles,
      ;; so this stub must NOT match (pre-fix it false-matched).
      {[:get "/articles"] {:reply {:ok [:should :not :match]}}}
      (rf/dispatch-sync [:azrcs/list2])
      (let [db (await-reply! #(some? (:result %)) 2000)]
        (is (= :failure (get-in db [:result :kind]))
            "the stale original-url key did NOT match the post-`:before` url")
        (is (= "no stub matched" (get-in db [:result :failure :message]))
            "the no-stub-matched synthetic failure fired (not the stale :ok)")
        (is (= "/v2/articles" (get-in db [:result :failure :url]))
            "the no-match failure reports the FINAL post-`:before` url")))))

(deftest stub-url-erasing-before-throws-bad-request-rf2-azrcs
  (testing "rf2-azrcs (complement) — a `:before` that BLANKS the url makes
            the stub throw the production `:rf.error/http-bad-request` and
            dispatch NO synthetic reply (pre-fix the stub keyed off the
            original valid url and returned a stubbed reply, masking the
            invalid request the real handler would reject)"
    (rf/reg-http-interceptor :azrcs/url-eraser
      {:before (fn [ctx] (assoc-in ctx [:request :url] nil))})
    ;; Install the stub fx directly so the throw is observable (a throw
    ;; inside dispatch-sync's fx phase is swallowed into the error sink).
    (let [recorded (atom [])
          original (late-bind/get-fn :router/dispatch!)]
      (late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! recorded conj [ev opts])))
      (try
        (re-frame.http.test-support/install-managed-request-stubs!
          {[:get "/x"] {:reply {:ok {:stubbed true}}}})
        (let [stub-fx (registrar/handler :fx :rf.http/managed-test-stub)
              ex      (try (stub-fx {:frame :rf/default :event [:azrcs/erase]}
                                    {:request {:method :get :url "/x"}})
                           nil
                           (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "the url-erasing :before made the stub throw")
          (is (= :rf.error/http-bad-request (:rf.error/id (ex-data ex)))
              "the throw is the canonical production bad-request, not a stubbed reply")
          (is (empty? @recorded)
              "NO synthetic reply was dispatched — the invalid request is rejected, not masked"))
        (finally
          (re-frame.http.test-support/uninstall-managed-request-stubs!)
          (late-bind/set-fn! :router/dispatch! original))))))

;; ---- 11a-vn8qjv. scoped stubs compose under nesting -----------------------
;;
;; rf2-vn8qjv (issue 2) — `with-managed-request-stubs*` must be stack-safe
;; for nested lexical scopes. Pre-fix every scope keyed off ONE global stub
;; fx id (`:rf.http/managed-test-stub`): the inner scope's install replaced
;; the outer handler and the inner's `finally` CLEARED it, so an outer-scope
;; dispatch after the inner exit routed to a now-absent fx. The fix mints a
;; UNIQUE fx id per scope and binds the override to that id, so the outer
;; scope's fx + override survive a fully-nested inner scope.

(deftest scoped-stubs-compose-under-nesting-rf2-vn8qjv
  (testing "rf2-vn8qjv — an outer A stub wraps an inner B stub; after the
            inner B scope exits, a later outer-scope request still routes to
            the A stub (pre-fix the inner's teardown cleared the shared fx and
            the outer dispatch hit a missing fx / wrong handler)"
    ;; Distinct event + route per call site so each scope keys off its own url.
    (rf/reg-event :vn8qjv/load-a
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :result-a reply)}
          {:fx [[:rf.http/managed {:request {:method :get :url "/a"} :decode :json}]]})))
    (rf/reg-event :vn8qjv/load-b
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :result-b reply)}
          {:fx [[:rf.http/managed {:request {:method :get :url "/b"} :decode :json}]]})))
    (rf/with-managed-request-stubs
      {[:get "/a"] {:reply {:ok {:from :outer-a}}}}
      ;; Inner scope B — distinct route + reply. Its install + teardown must
      ;; not disturb the outer A scope.
      (rf/with-managed-request-stubs
        {[:get "/b"] {:reply {:ok {:from :inner-b}}}}
        (rf/dispatch-sync [:vn8qjv/load-b])
        (let [db (await-reply! #(some? (:result-b %)) 2000)]
          (is (= {:from :inner-b} (get-in db [:result-b :value]))
              "inner B scope routed to the B stub")))
      ;; Inner scope has exited. The outer A scope's stub MUST still be live.
      (rf/dispatch-sync [:vn8qjv/load-a])
      (let [db (await-reply! #(some? (:result-a %)) 2000)]
        (is (= :success (get-in db [:result-a :kind]))
            "outer A scope still synthesised a reply after the inner B exit")
        (is (= {:from :outer-a} (get-in db [:result-a :value]))
            "outer A scope still routed to the A stub — not cleared by inner B teardown")))))

;; ---- 11a-vn8qjv (lower-level). install/uninstall stack + no fx leak --------
;;
;; rf2-vn8qjv (issue 2) — the lower-level install/uninstall surface keeps the
;; STABLE documented id (`:rf.http/managed-test-stub`, the `:fx-overrides`
;; target users hardcode), but install snapshots the prior handler and
;; uninstall restores it, so a nested install/uninstall pair leaves the outer
;; install intact; a balanced top-level pair leaks no fx.

(deftest lower-level-install-uninstall-stack-discipline-rf2-vn8qjv
  (testing "rf2-vn8qjv — nested install/uninstall on the stable id restores the
            outer handler; balanced top-level pair leaks no test fx"
    (let [stub-id :rf.http/managed-test-stub]
      (is (nil? (registrar/handler :fx stub-id))
          "no stub fx registered before install")
      (re-frame.http.test-support/install-managed-request-stubs!
        {[:get "/outer"] {:reply {:ok {:from :outer}}}})
      (let [outer-handler (registrar/handler :fx stub-id)]
        (is (some? outer-handler) "outer install registered the stub fx")
        ;; Nested install replaces the handler in place.
        (re-frame.http.test-support/install-managed-request-stubs!
          {[:get "/inner"] {:reply {:ok {:from :inner}}}})
        (is (not (identical? outer-handler (registrar/handler :fx stub-id)))
            "inner install replaced the handler")
        ;; Inner uninstall RESTORES the outer handler (stack discipline).
        (re-frame.http.test-support/uninstall-managed-request-stubs!)
        (is (identical? outer-handler (registrar/handler :fx stub-id))
            "inner uninstall restored the outer install's handler")
        ;; Outer uninstall clears (no prior on the stack).
        (re-frame.http.test-support/uninstall-managed-request-stubs!)
        (is (nil? (registrar/handler :fx stub-id))
            "balanced top-level install/uninstall leaves no leaked test fx")
        ;; Idempotent: an extra uninstall is a safe no-op.
        (re-frame.http.test-support/uninstall-managed-request-stubs!)
        (is (nil? (registrar/handler :fx stub-id))
            "extra uninstall is an idempotent no-op")))))

;; ---- 11b. canned-stub fxs gated on explicit test-support require (rf2-cdmle)
;;
;; Per rf2-cdmle (follow-up to rf2-zk08x): the gate that decides whether
;; the canned-stub fxs (`:rf.http/managed-canned-success` /
;; `:rf.http/managed-canned-failure`) register moved from
;; `(when interop/debug-enabled? ...)` inside `re-frame.http.managed` to
;; the require boundary itself. The fxs now register under
;; `re-frame.http.test-support`; production code paths must not require
;; that namespace.
;;
;; Why the change: `interop/debug-enabled?` is unconditionally true on the
;; JVM, so the prior gate left the canned-stub fx ids registered as
;; production-default API on JVM/SSR builds — discoverable via
;; `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}`
;; from any handler in production code. The require-boundary gate makes
;; the absence load-bearing on every host: JVM/SSR sees classpath
;; absence; CLJS `:advanced` sees module-graph DCE (the existing
;; `scripts/check-elision.cjs` sentinels still pin the bundle absence).
;;
;; This file's reset-runtime fixture re-requires
;; `re-frame.http.test-support :reload` between tests, so the canned
;; stubs ARE registered for the bulk of the suite (the methodology
;; check below pins that). The standalone negative-assertion test that
;; exercises the absence path (test-support absent → canned stubs
;; absent) lives in `re-frame.http-test-support-absent-test` so a
;; sibling `:require` in this ns can't reintroduce the fxs and false-
;; pass the absence assertion.

(deftest canned-stub-fxs-registered-when-test-support-required
  (testing "rf2-cdmle methodology check — with re-frame.http.test-support
            in the require closure (this ns requires it at the top), the
            two canonical canned-stub fxs MUST be registered. The
            absence test in re-frame.http-test-support-absent-test would
            be vacuous if this side did not actually register the stubs."
    ;; The fixture has just reloaded http-test-support, so the canned
    ;; stubs are present. The production-eligible fxs are present too.
    (is (some? (registrar/lookup :fx :rf.http/managed))
        ":rf.http/managed is dev+prod — always registered by re-frame.http.managed")
    (is (some? (registrar/lookup :fx :rf.http/managed-abort))
        ":rf.http/managed-abort is dev+prod — always registered by re-frame.http.managed")
    (is (some? (registrar/lookup :fx :rf.http/managed-canned-success))
        ":rf.http/managed-canned-success registered when re-frame.http.test-support is required")
    (is (some? (registrar/lookup :fx :rf.http/managed-canned-failure))
        ":rf.http/managed-canned-failure registered when re-frame.http.test-support is required")))

;; ---- 12. decode reflection metadata ---------------------------------------

(deftest decode-reflection-metadata
  (testing ":rf.http/decode-schemas declared on the handler is queryable via handler-meta"
    (rf/reg-event :article/load
      {:doc                    "Load an article."
       :rf.http/decode-schemas [::ArticleResponse]}
      (fn [_ _] {}))
    (let [m (rf/handler-meta :event :article/load)]
      (is (= [::ArticleResponse] (:rf.http/decode-schemas m))))))

;; ---- actor-in-flight-snapshot shape contract (rf2-kyl7) -------------------
;;
;; Per rf2-kyl7: `actor-in-flight-snapshot` and `in-flight-snapshot`
;; are read by assertions across http_actor_destroy_cancellation_test
;; and http_managed_machine_test, but no test PINS THE SHAPE of the
;; snapshot — which keys, which values. A wire-protocol regression
;; (e.g. someone changing the value to a single handle instead of a
;; vector of handles) would slip through every existing assertion.
;;
;; Source: http_managed.cljc:177-189. Storage is:
;;   `actor-in-flight` : actor-id → vector of handle maps
;;   `in-flight`       : request-id → single handle map
;; Each handle map carries `:abort-fn`, `:url`, plus the framework
;; stamps `:request-id` and `:actor-id` (when applicable).

(defn- await-condition!
  "Wait up to `timeout-ms` for `(pred)` to be truthy. Returns `:done`
  on success; throws `ex-info` carrying `:rf.error/id`
  `:rf.error/poll-until-timeout` on timeout. Thin alias over
  `test-support/poll-until` (rf2-fun38)."
  [pred timeout-ms]
  (test-support/poll-until pred {:timeout-ms timeout-ms
                                 :label "http-managed condition"})
  :done)

(deftest actor-in-flight-snapshot-shape
  (testing "actor-in-flight-snapshot is a map keyed by actor-id, value is a
            vector of handle maps each carrying :abort-fn / :url / :request-id
            / :actor-id. in-flight-snapshot is keyed by request-id."
    (let [latch (CountDownLatch. 1)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Block — both requests must remain in-flight while the test
              ;; reads the snapshots.
              (.await latch 10 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{}")))]
      (try
        ;; Issue two requests from inside a spawned actor so both land
        ;; in the actor-in-flight index. The actor pattern mirrors
        ;; http_actor_destroy_cancellation_test (2).
        (require 're-frame.machines :reload)
        (rf/reg-machine :kyl7/worker
          {:initial :idle
           :data    {:port port}
           :actions
           {:fire-two
            (fn [{data :data}]
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" (:port data) "/a")}
                      :request-id :kyl7/a
                      :decode     :json
                      :on-success nil
                      :on-failure nil}]
                    [:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" (:port data) "/b")}
                      :request-id :kyl7/b
                      :decode     :json
                      :on-success nil
                      :on-failure nil}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-two}}})
        (rf/reg-machine :kyl7/sup
          {:initial :idle
           :states
           {:idle    {:on {:start :working}}
            :working {:spawn {:machine-id :kyl7/worker
                               :start      [:start]}}}})
        (rf/dispatch-sync [:kyl7/sup [:start]])

        ;; Wait for both requests to be in-flight under the same actor.
        (await-condition!
          #(let [snap (http-managed/actor-in-flight-snapshot)]
             (and (= 1 (count snap))
                  (= 2 (count (val (first snap))))))
          5000)

        ;; ---- actor-in-flight-snapshot shape ----
        (let [actor-snap (http-managed/actor-in-flight-snapshot)]
          (is (map? actor-snap)
              "actor-in-flight-snapshot returns a map")
          (is (= 1 (count actor-snap))
              "one actor key — the spawned :kyl7/worker child")
          (let [[actor-id handles] (first actor-snap)]
            (is (keyword? actor-id)
                "actor-id is a keyword (the spawned actor's address)")
            (is (= :kyl7/worker#1 actor-id)
                "actor-id is the deterministic spawn id of the child")
            (is (vector? handles)
                "value under each actor-id is a vector (multiple in-flight requests
                 from the same actor accumulate as siblings)")
            (is (= 2 (count handles))
                "two in-flight requests from this actor")
            (doseq [h handles]
              (is (map? h)
                  "each handle is a map")
              (is (fn? (:abort-fn h))
                  ":abort-fn is the no-arg cancellation fn")
              (is (string? (:url h))
                  ":url stamps the resolved URL for diagnostic visibility")
              (is (= actor-id (:actor-id h))
                  ":actor-id stamped on the handle matches its index key")
              (is (#{:kyl7/a :kyl7/b} (:request-id h))
                  ":request-id stamped on the handle matches the user-supplied id"))))

        ;; ---- in-flight-snapshot shape (request-id-keyed) ----
        (let [req-snap (http-managed/in-flight-snapshot)]
          (is (map? req-snap)
              "in-flight-snapshot returns a map")
          (is (= 2 (count req-snap))
              "two request-id keys — one per in-flight request")
          (is (= #{:kyl7/a :kyl7/b} (set (keys req-snap)))
              "request-id keys match the user-supplied :request-id values")
          (doseq [[req-id handle] req-snap]
            (is (map? handle)
                "each value is a SINGLE handle map (NOT a vector — unlike actor index)")
            (is (= req-id (:request-id handle))
                ":request-id on the handle matches its index key")
            (is (fn? (:abort-fn handle))
                ":abort-fn is the cancellation fn (same as actor-index handle)")))

        ;; Release so the JDK sockets close cleanly.
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- 13. timeout failure category -----------------------------------------

(deftest jvm-timeout-failure
  (testing "per-attempt timeout fires :rf.http/timeout"
    (let [latch (CountDownLatch. 1)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (.await latch 10 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"too\":\"late\"}")))]
      (try
        (rf/reg-event :slow/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                      :timeout-ms 80
                      :decode     :json}]]})))
        (rf/dispatch-sync [:slow/load])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/timeout (get-in db [:reply :failure :kind]))))
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- 14. supersede on same :request-id (rf2-lxd3) -------------------------
;;
;; Per rf2-lxd3 decision A: when a fresh request supersedes a prior one
;; with the same `:request-id`, the prior request's `:on-failure` reply
;; is NOT dispatched (semantic = the new request replaces the old one,
;; debounce-search mental model). The supersede event still emits to
;; the trace bus (`:rf.http/aborted` with `:reason :request-id-superseded`);
;; consumers wanting abort telemetry subscribe via `register-listener!`.

(deftest jvm-supersede-does-not-fire-on-failure
  (testing "rf2-lxd3 — superseding a request with the same :request-id MUST NOT
            fire the prior request's :on-failure. The :on-success is silenced
            (nil) on both requests so the test isolates failure-reply behaviour
            from the JVM transport's natural-completion path."
    (let [latch         (CountDownLatch. 1)
          a-failed?     (atom false)
          b-success?    (atom false)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Block long enough for the second dispatch to supersede the
              ;; first BEFORE the server responds.
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :search/run
          (fn [_ [_ q]]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q?" q)}
                    :request-id :search
                    :decode     :json
                    ;; Silence success on the prior request — only the
                    ;; supersede-driven :on-failure dispatch is under test.
                    :on-success nil
                    :on-failure [:search/a-failed]}]]}))
        (rf/reg-event :search/run-superseding
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q?fresh")}
                    :request-id :search
                    :decode     :json
                    :on-failure nil
                    :on-success [:search/b-ok]}]]}))
        (rf/reg-event :search/a-failed (fn [{:keys [db]} _] (reset! a-failed? true) {:db db}))
        (rf/reg-event :search/b-ok     (fn [{:keys [db]} _] (reset! b-success? true) {:db db}))

        (rf/dispatch-sync [:search/run "stale"])
        ;; Let the first request reach in-flight.
        (await-condition!
          #(seq (http-managed/in-flight-snapshot))
          2000)
        ;; Fire the superseding request — same :request-id.
        (rf/dispatch-sync [:search/run-superseding])
        ;; Release the server so the second request can complete.
        (.countDown latch)
        ;; Wait for the second request's success reply.
        (await-condition! #(true? @b-success?) 5000)
        ;; Timer-semantics sleep (rf2-fun38): the PRIOR request's
        ;; :on-failure MUST NOT have fired — we are proving absence.
        ;; Extra 100ms quiescence rules out any delayed dispatch from
        ;; the abort or natural-completion path within window.
        (Thread/sleep 100)
        (is (false? @a-failed?)
            "the superseded request's :on-failure must NOT fire (rf2-lxd3 fix)")
        (is (true? @b-success?)
            "the superseding request's :on-success DOES fire")
        (finally (stop-server! srv))))))

(deftest jvm-supersede-still-emits-trace-event
  (testing "rf2-lxd3 — supersede still emits :rf.http/aborted trace event with
            :reason :request-id-superseded so register-listener! consumers
            keep visibility"
    (let [latch    (CountDownLatch. 1)
          events   (atom [])
          cb-id    ::lxd3-trace
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (trace/register-listener! cb-id
                                  (fn [ev]
                                    (when (= :rf.http/aborted (:operation ev))
                                      (swap! events conj ev))))
        (rf/reg-event :search/run
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q1")}
                    :request-id :search
                    :decode     :json}]]}))
        (rf/reg-event :search/run-superseding
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q2")}
                    :request-id :search
                    :decode     :json}]]}))

        (rf/dispatch-sync [:search/run])
        (await-condition!
          #(seq (http-managed/in-flight-snapshot))
          2000)
        (rf/dispatch-sync [:search/run-superseding])
        (await-condition! #(seq @events) 2000)

        (let [ev (first @events)
              tags (:tags ev)]
          (is (= :rf.http/aborted (:operation ev))
              "supersede emits :rf.http/aborted trace event")
          (is (= :request-id-superseded (:reason tags))
              ":reason :request-id-superseded distinguishes supersede from :user / :actor-destroyed")
          (is (= :search (:request-id tags))
              ":request-id rides on the trace event"))

        (.countDown latch)
        (finally
          (trace/unregister-listener! cb-id)
          (stop-server! srv))))))

(deftest jvm-non-superseded-abort-still-fires-reply
  (testing "rf2-lxd3 regression guard — a non-supersede abort (manual
            :rf.http/managed-abort) STILL fires :on-failure as before.
            Only :reason :request-id-superseded suppresses the reply."
    (let [latch        (CountDownLatch. 1)
          reply-fired? (atom false)
          reply-data   (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (.await latch 5 TimeUnit/SECONDS)
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (rf/reg-event :slow/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                    :request-id :slow
                    :decode     :json
                    :on-failure [:slow/failed]}]]}))
        (rf/reg-event :slow/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :slow]]}))
        (rf/reg-event :slow/failed
          (fn [{:keys [db]} [_ payload]]
            (reset! reply-fired? true)
            (reset! reply-data payload)
            {:db db}))

        (rf/dispatch-sync [:slow/load])
        (await-condition!
          #(seq (http-managed/in-flight-snapshot))
          2000)
        ;; User-initiated abort — the abort fn passes `:user` as the reason.
        (rf/dispatch-sync [:slow/abort])
        (await-condition! #(true? @reply-fired?) 2000)

        (is (true? @reply-fired?)
            "non-supersede abort STILL dispatches :on-failure")
        ;; Per build-reply-event: explicit :on-failure [:slow/failed] appends
        ;; the reply payload as the last arg — the handler receives the
        ;; payload directly (NOT wrapped under :rf/reply).
        (let [reply @reply-data]
          (is (= :failure (:kind reply))
              "the reply is a :failure reply")
          (is (= :rf.http/aborted (-> reply :failure :kind))
              "failure kind is :rf.http/aborted")
          (is (not= :request-id-superseded (-> reply :failure :reason))
              ":reason is NOT :request-id-superseded (this is the regression guard)"))

        (.countDown latch)
        (finally (stop-server! srv))))))

;; ===========================================================================
;; rf2-ohwgm — end-to-end coverage for the three untested spec contracts the
;; http test-coverage audit (ai/findings/2026-05-21-testcov-http.md) flagged:
;;   G1  — Malli schema decode failure → :rf.http/decode-failure
;;          :schema-validation-failure? true (and too-many-keys e2e)
;;   G2  — :accept returning {:failure ..} → :rf.http/accept-failure reply
;;          carrying :detail + :decoded
;;   G3  — request-side encoding (params query-string + :request-content-type
;;          body) and the Content-Type clash guard, observed at a real server
;; These ride the real java.net.http.HttpClient transport + in-process server
;; so the WHOLE managed cascade (transport → decode → accept → classify →
;; reply addressing) is exercised, not just the pure helpers.
;; ===========================================================================

;; ---- G1: Malli schema decode — validation failure e2e ---------------------

(deftest jvm-schema-validation-failure-classifies-as-decode-failure
  (testing "rf2-ohwgm — a 200 JSON response that parses but FAILS a Malli
            :decode schema classifies as :rf.http/decode-failure with
            :schema-validation-failure? true (Spec 014 §Classification
            order step 3; http_transport.cljc:721-726)"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; id arrives as a JSON string; the schema requires :int and
              ;; the json-transformer can't coerce "oops" → int, so
              ;; validation fails.
              (write-response! ex 200 "application/json" "{\"id\":\"oops\"}")))]
      (try
        (rf/reg-event :thing/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/thing")}
                      :decode  [:map [:id :int]]}]]})))
        (rf/dispatch-sync [:thing/load])
        (let [db      (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/decode-failure (:kind failure))
              "schema validation failure is a decode failure")
          (is (true? (:schema-validation-failure? failure))
              "the :schema-validation-failure? slot is set true (Spec 014 line 410)"))
        (finally (stop-server! srv))))))

(deftest jvm-schema-decode-success-coerces-value
  (testing "rf2-ohwgm — a 200 JSON response that satisfies the Malli
            :decode schema returns the coerced value as the :success reply
            (string status coerced to keyword by the json-transformer)"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json"
                               "{\"id\":7,\"status\":\"active\"}")))]
      (try
        (rf/reg-event :thing/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/thing")}
                      :decode  [:map [:id :int] [:status :keyword]]}]]})))
        (rf/dispatch-sync [:thing/load])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :success (get-in db [:reply :kind])))
          (is (= {:id 7 :status :active} (get-in db [:reply :value]))
              "the schema coerces the string status to a keyword e2e"))
        (finally (stop-server! srv))))))

(deftest jvm-too-many-keys-cap-classifies-as-decode-failure
  (testing "rf2-ohwgm — the :rf.http/max-decoded-keys cap threaded into the
            schema-branch decode surfaces a :too-many-keys throw as
            :rf.http/decode-failure end-to-end (NOT masked behind a schema
            rejection; rf2-wu1n5)"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json"
                               "{\"a\":1,\"b\":2,\"c\":3}")))]
      (try
        (rf/reg-event :thing/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request                  {:url (str "http://127.0.0.1:" port "/thing")}
                      :decode                   [:map-of :keyword :int]
                      :rf.http/max-decoded-keys 2}]]})))
        (rf/dispatch-sync [:thing/load])
        (let [db      (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/decode-failure (:kind failure))
              "an over-cap payload classifies as a decode failure")
          ;; The cap-throw is :rf.error/malformed-json, NOT a schema
          ;; validation, so :schema-validation-failure? must be falsey.
          (is (not (true? (:schema-validation-failure? failure)))
              "too-many-keys is a malformed-json cap-throw, not a schema rejection")
          ;; rf2-mdxd7 — Spec 014 §Keyword-interning cap (lines 145, 285,
          ;; 289) mandates the overflow surface as :rf.http/decode-failure
          ;; with :reason :too-many-keys and the configured :limit. Both
          ;; must reach the dispatched failure map so a caller branching
          ;; on :reason sees the spec-documented shape (and a DoS-cap
          ;; overflow is programmatically distinguishable from an ordinary
          ;; JSON syntax error, since :schema-validation-failure? is false
          ;; for both).
          (is (= :too-many-keys (:reason failure))
              "the cap-overflow surfaces :reason :too-many-keys per Spec 014 §Keyword-interning cap")
          (is (= 2 (:limit failure))
              "the configured :rf.http/max-decoded-keys cap rides at :limit per Spec 014"))
        (finally (stop-server! srv))))))

(deftest jvm-bare-json-syntax-error-carries-no-too-many-keys-reason
  (testing "rf2-mdxd7 — a 200 response whose body is malformed JSON (a
            plain syntax error, NOT a cap overflow) still classifies as
            :rf.http/decode-failure but carries NEITHER :reason :too-many-keys
            NOR :limit — those slots are reserved for the DoS-cap shape, so
            a caller can use :reason as a discriminator"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Truncated JSON — Cheshire throws a parse error that is
              ;; NOT the structured :rf.error/malformed-json cap ex-info.
              (write-response! ex 200 "application/json" "{\"a\": ")))]
      (try
        (rf/reg-event :thing/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/thing")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:thing/load])
        (let [db      (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :rf.http/decode-failure (:kind failure))
              "a JSON syntax error is still a decode failure")
          (is (nil? (:reason failure))
              "a bare syntax error carries no :reason — only the cap-overflow shape does")
          (is (nil? (:limit failure))
              "a bare syntax error carries no :limit — only the cap-overflow shape does"))
        (finally (stop-server! srv))))))

;; ---- G2: :accept returning {:failure ..} → :rf.http/accept-failure --------

(deftest jvm-accept-failure-classifies-and-carries-detail-and-decoded
  (testing "rf2-ohwgm — an :accept fn that returns {:failure ..} on a 2xx
            response produces a :rf.http/accept-failure reply carrying the
            user :detail and the pre-accept :decoded value
            (http_transport.cljc:523-530; Spec 014 §`:accept` +
            §Classification order step 4)"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; 200 OK, but the domain payload says the operation failed —
              ;; the :accept fn turns a transport-success into a domain
              ;; failure.
              (write-response! ex 200 "application/json"
                               "{\"ok\":false,\"reason\":\"quota\"}")))]
      (try
        (rf/reg-event :op/run
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/op")}
                      :decode  :json
                      :accept  (fn [decoded]
                                 (if (:ok decoded)
                                   {:ok decoded}
                                   {:failure {:kind   :domain-rejected
                                              :reason (:reason decoded)}}))}]]})))
        (rf/dispatch-sync [:op/run])
        (let [db      (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/accept-failure (:kind failure))
              "an :accept-rejected 2xx classifies as :rf.http/accept-failure")
          (is (= {:kind :domain-rejected :reason "quota"} (:detail failure))
              "the user :failure map rides through verbatim as :detail")
          (is (= {:ok false :reason "quota"} (:decoded failure))
              "the pre-accept decoded value rides through as :decoded"))
        (finally (stop-server! srv))))))

;; ---- G3: request-side encoding observed at the server ---------------------

(deftest jvm-request-params-encoded-into-query-string
  (testing "rf2-ohwgm — :params is encoded onto the request URL as a query
            string (keyword keys → name, values escaped) and arrives at
            the server (http_encoding.cljc:50-67 via run-attempt!)"
    (let [seen-query (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; getRawQuery preserves the percent-encoding produced by
              ;; the client; getQuery would decode it back, hiding whether
              ;; the value was escaped on the wire.
              (reset! seen-query (.getRawQuery (.getRequestURI ex)))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :search/run
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url    (str "http://127.0.0.1:" port "/search")
                                :params {:q "a b" :page 2}}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:search/run])
        (await-reply! #(some? (:reply %)) 5000)
        (let [q @seen-query]
          (is (some? q) "the server saw a query string")
          (is (clojure.string/includes? q "q=a%20b")
              "the space-bearing value is percent-escaped in the query")
          (is (clojure.string/includes? q "page=2")
              "the keyword key is rendered via name; numeric value coerced"))
        (finally (stop-server! srv))))))

(deftest jvm-request-content-type-encodes-body-and-sets-header
  (testing "rf2-ohwgm — :request-content-type :json encodes the body and
            sets the Content-Type header; the server observes both
            (http_encoding.cljc:76-102 + the header-set in run-attempt!)"
    (let [seen-ct   (atom nil)
          seen-body (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (reset! seen-ct (.getFirst (.getRequestHeaders ex) "Content-Type"))
              (reset! seen-body (slurp (.getRequestBody ex)))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :item/create
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:method               :post
                                :url                  (str "http://127.0.0.1:" port "/items")
                                :body                 {:name "widget"}
                                :request-content-type :json}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:item/create])
        (await-reply! #(some? (:reply %)) 5000)
        (is (= "application/json" @seen-ct)
            ":request-content-type :json sets the Content-Type header")
        (is (= {:name "widget"} (util-json/json-parse @seen-body))
            "the body is JSON-encoded and round-trips at the server")
        (finally (stop-server! srv))))))

(deftest jvm-explicit-content-type-header-wins-clash-guard
  (testing "rf2-ohwgm — when the request already carries a Content-Type
            header, run-attempt! does NOT overwrite it with the
            encode-body content-type (the clash guard at
            http_transport.cljc:755-757)"
    (let [seen-cts (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Capture ALL Content-Type header values so a double-set
              ;; (guard regression) would show up as >1 entry.
              (reset! seen-cts (vec (.get (.getRequestHeaders ex) "Content-Type")))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :item/create
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:method               :post
                                :url                  (str "http://127.0.0.1:" port "/items")
                                ;; Caller pre-sets Content-Type; encode-body
                                ;; would otherwise also propose application/json.
                                :headers              {"Content-Type" "application/vnd.custom+json"}
                                :body                 {:name "widget"}
                                :request-content-type :json}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:item/create])
        (await-reply! #(some? (:reply %)) 5000)
        (let [cts @seen-cts]
          (is (= ["application/vnd.custom+json"] cts)
              "the caller's explicit Content-Type is preserved and NOT
               supplemented by the encode-body content-type (clash guard)"))
        (finally (stop-server! srv))))))

;; ===========================================================================
;; rf2-rznrz — finding 1: multi-valued REQUEST headers reach the wire as
;; repeated header instances, not a single malformed `["a" "b"]` value.
;; ===========================================================================

(deftest jvm-vector-request-header-sent-as-repeated-values
  (testing "rf2-rznrz — a request header whose value is a vector of strings
            (the documented multi-valued shape) arrives at the server as N
            repeated header instances, NOT one line carrying the stringified
            vector. The JVM transport now calls .header once per element."
    (let [seen-accept (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; .get returns the List of ALL values for the header name —
              ;; one entry per wire instance.
              (reset! seen-accept (vec (.get (.getRequestHeaders ex) "X-Multi")))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :multihdr/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url     (str "http://127.0.0.1:" port "/m")
                                :headers {"X-Multi" ["alpha" "beta" "gamma"]}}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:multihdr/load])
        (await-reply! #(some? (:reply %)) 5000)
        (let [vs @seen-accept]
          (is (= 3 (count vs))
              "the vector value produced THREE separate wire header instances")
          (is (= ["alpha" "beta" "gamma"] vs)
              "each element arrives as its own value, in order — NOT a single
               '[\"alpha\" \"beta\" \"gamma\"]' stringified line")
          (is (not-any? #(clojure.string/includes? % "[")
                        vs)
              "no element carries a serialised-vector bracket (the prior bug)"))
        (finally (stop-server! srv))))))

(deftest jvm-scalar-request-header-still-single-value
  (testing "rf2-rznrz — a scalar request header value is unchanged: one wire
            instance carrying the stringified scalar (the 99% path)"
    (let [seen (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (reset! seen (vec (.get (.getRequestHeaders ex) "X-One")))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :scalarhdr/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url     (str "http://127.0.0.1:" port "/s")
                                :headers {"X-One" "only"}}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:scalarhdr/load])
        (await-reply! #(some? (:reply %)) 5000)
        (is (= ["only"] @seen)
            "a scalar header value is a single wire instance")
        (finally (stop-server! srv))))))

;; ===========================================================================
;; rf2-rznrz — finding 2: :accept phase isolation + shape validation.
;;   - an :accept THROW classifies as :rf.http/accept-failure (NOT
;;     :rf.http/decode-failure — the prior fused try/catch misclassified it);
;;   - a MALFORMED :accept return (nil / map without :ok/:failure) classifies
;;     as :rf.http/accept-failure and ALWAYS dispatches a reply (previously
;;     it stranded the caller with no reply at all).
;; ===========================================================================

(deftest jvm-accept-throw-classifies-as-accept-failure-not-decode-failure
  (testing "rf2-rznrz — an :accept fn that THROWS on a 2xx response
            classifies as :rf.http/accept-failure (step-4 error), NOT
            :rf.http/decode-failure (step-3). The decode succeeded; the
            accept phase is now isolated in its own try/catch."
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :acceptthrow/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/a")}
                      :decode  :json
                      :accept  (fn [_decoded]
                                 (throw (ex-info "accept boom" {})))}]]})))
        (rf/dispatch-sync [:acceptthrow/load])
        (let [db      (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind]))
              "a thrown :accept produces a FAILURE reply (caller is not stranded)")
          (is (= :rf.http/accept-failure (:kind failure))
              "the throw classifies as :rf.http/accept-failure, NOT
               :rf.http/decode-failure — decode succeeded; accept is the failing phase")
          (is (= {:ok true} (:decoded failure))
              "the pre-accept decoded value rides through as :decoded for context"))
        (finally (stop-server! srv))))))

(deftest jvm-accept-nil-return-classifies-as-accept-failure-and-replies
  (testing "rf2-rznrz — an :accept fn returning nil (a malformed shape) is
            classified as :rf.http/accept-failure and ALWAYS dispatches a
            reply. Previously this fell through finalise-success!'s cond with
            no matching branch: the in-flight request was cleared and NO reply
            was dispatched — the caller hung forever."
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :acceptnil/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/n")}
                      :decode  :json
                      ;; Returns nil — neither {:ok ..} nor {:failure ..}.
                      :accept  (fn [_decoded] nil)}]]})))
        (rf/dispatch-sync [:acceptnil/load])
        (let [db      (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind]))
              "a nil :accept return STILL dispatches a reply (no infinite hang)")
          (is (= :rf.http/accept-failure (:kind failure))
              "the malformed return classifies as :rf.http/accept-failure")
          (is (= {:ok true} (:decoded failure))
              "the pre-accept decoded value rides through as :decoded"))
        (finally (stop-server! srv))))))

(deftest jvm-accept-map-without-ok-or-failure-classifies-as-accept-failure
  (testing "rf2-rznrz — an :accept fn returning a map that carries NEITHER
            :ok NOR :failure is malformed and classifies as
            :rf.http/accept-failure with a reply (was a silent hang)"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-event :acceptbad/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/b")}
                      :decode  :json
                      ;; A map, but neither :ok nor :failure.
                      :accept  (fn [_decoded] {:status :weird})}]]})))
        (rf/dispatch-sync [:acceptbad/load])
        (let [db      (await-reply! #(some? (:reply %)) 5000)
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/accept-failure (:kind failure))
              "a map without :ok/:failure is a malformed accept return"))
        (finally (stop-server! srv))))))

(deftest jvm-accept-well-formed-ok-still-succeeds
  (testing "rf2-rznrz — a well-formed {:ok v} accept return is unaffected:
            the success reply carries v (regression guard for the phase split)"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"value\":42}")))]
      (try
        (rf/reg-event :acceptok/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/o")}
                      :decode  :json
                      :accept  (fn [decoded] {:ok (:value decoded)})}]]})))
        (rf/dispatch-sync [:acceptok/load])
        (let [db (await-reply! #(some? (:reply %)) 5000)]
          (is (= :success (get-in db [:reply :kind])))
          (is (= 42 (get-in db [:reply :value]))
              "a well-formed {:ok v} still projects the success value"))
        (finally (stop-server! srv))))))

;; ---- rf2-xmp74u — stub/canned request-chain failure honours TOP-LEVEL
;;      :sensitive? -------------------------------------------------------------
;;
;; Production `managed-handler` seeds the effective top-level `:sensitive?`
;; into the request middleware ctx (via `privacy/request-sensitive?`), so when
;; a `:before` throws, the `:rf.error/http-interceptor-failed` trace redacts
;; ALL query values + stamps `:sensitive?`. The canned/stub wrapper's
;; `run-request-chain` built `ctx0` WITHOUT that top-level flag, so a request
;; that opted in via the TOP-LEVEL `:sensitive? true` form (not the nested
;; `[:request :sensitive?]`) ran the stub `:before` chain non-sensitive and
;; leaked non-denylisted query values through the failure trace — a stub-path
;; secret leak + a test false-green relative to production.
;;
;; We key the proof on a NON-denylisted query param (`customer_email`): only
;; effective top-level sensitivity can scrub it. The chain's own `:sensitive-of`
;; reducer (rf2-rznrz) still recomputes from a `:before`-MARKED request, so this
;; seed is the pre-chain floor — exactly what production seeds.
;;
;; A `:before` throw inside `dispatch-sync`'s fx phase is swallowed into the
;; error sink, so (mirroring `stub-url-erasing-before-throws-rf2-azrcs`) we
;; drive the stub fx directly, with the trace listener capturing the emitted
;; failure event.

(deftest stub-request-chain-failure-honours-top-level-sensitive-rf2-xmp74u
  (testing "rf2-xmp74u — a route-map stub with TOP-LEVEL :sensitive? true and a
            throwing :before emits :rf.error/http-interceptor-failed redacting
            the NON-denylisted query value AND stamping :sensitive? — matching
            production, not the prior stub-path leak"
    (let [traces      (atom [])
          listener-id (gensym "xmp74u-stub-sensitive-")
          recorded    (atom [])
          original    (late-bind/get-fn :router/dispatch!)]
      (late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! recorded conj [ev opts])))
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        ;; A :before that throws AFTER the chain starts.
        (rf/reg-http-interceptor :xmp74u/boom
          {:before (fn [_ctx] (throw (ex-info "kaboom" {})))})
        (re-frame.http.test-support/install-managed-request-stubs!
          {[:get "https://api.example.invalid/v1"] {:reply {:ok {:stubbed true}}}})
        (let [stub-fx (registrar/handler :fx :rf.http/managed-test-stub)
              ;; TOP-LEVEL :sensitive? true (NOT [:request :sensitive?]).
              ;; customer_email is NOT in the query-param denylist, so it
              ;; only redacts when the request is effectively sensitive.
              ex      (try
                        (stub-fx {:frame :rf/default :event [:xmp74u/load]}
                                 {:request    {:method :get
                                               :url    "https://api.example.invalid/v1?customer_email=alice%40example.com&page=2"}
                                  :sensitive? true})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "the throwing :before propagated out of the stub chain")
          (is (= :rf.error/http-interceptor-failed (:rf.error/id (ex-data ex)))
              "the throw is the canonical interceptor-failed classification")
          (is (empty? @recorded)
              "NO synthetic reply was dispatched — the failed chain rejects the request"))
        (let [w    (first (filter #(= :rf.error/http-interceptor-failed (:operation %)) @traces))
              tags (:tags w)]
          (is (some? w) "the interceptor-failed trace event was emitted")
          ;; A sensitive request scrubs ALL query values (broader than the
          ;; denylist). The load-bearing signal is customer_email — which the
          ;; denylist alone leaves verbatim — being redacted: that can only
          ;; happen if the stub chain seeded the TOP-LEVEL :sensitive? flag.
          (is (= "https://api.example.invalid/v1?customer_email=:rf/redacted&page=:rf/redacted"
                 (:url tags))
              "the NON-denylisted query value is redacted — proving the stub
               run-request-chain seeded top-level :sensitive? like production")
          (is (true? (:sensitive? w))
              ":sensitive? stamped on the stub-path failure trace, matching production"))
        (finally
          (re-frame.http.test-support/uninstall-managed-request-stubs!)
          (trace/unregister-listener! listener-id)
          (late-bind/set-fn! :router/dispatch! original))))))

(deftest stub-request-chain-failure-non-sensitive-leaves-query-value-rf2-xmp74u
  (testing "rf2-xmp74u (complement) — WITHOUT :sensitive?, the same throwing
            :before leaves the NON-denylisted query value verbatim and does NOT
            stamp :sensitive? (the seed is gated on actual sensitivity, not a
            blanket scrub)"
    (let [traces      (atom [])
          listener-id (gensym "xmp74u-stub-nonsensitive-")
          recorded    (atom [])
          original    (late-bind/get-fn :router/dispatch!)]
      (late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! recorded conj [ev opts])))
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        (rf/reg-http-interceptor :xmp74u/boom2
          {:before (fn [_ctx] (throw (ex-info "kaboom" {})))})
        (re-frame.http.test-support/install-managed-request-stubs!
          {[:get "https://api.example.invalid/v1"] {:reply {:ok {:stubbed true}}}})
        (let [stub-fx (registrar/handler :fx :rf.http/managed-test-stub)
              _ex     (try
                        (stub-fx {:frame :rf/default :event [:xmp74u/load2]}
                                 {:request {:method :get
                                            :url    "https://api.example.invalid/v1?customer_email=alice%40example.com&page=2"}})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (empty? @recorded) "NO synthetic reply was dispatched"))
        (let [w    (first (filter #(= :rf.error/http-interceptor-failed (:operation %)) @traces))
              tags (:tags w)]
          (is (some? w) "the interceptor-failed trace event was emitted")
          (is (= "https://api.example.invalid/v1?customer_email=alice%40example.com&page=2"
                 (:url tags))
              "non-sensitive: the non-denylisted query value rides through verbatim")
          (is (not (true? (:sensitive? w)))
              ":sensitive? not stamped for a non-sensitive request"))
        (finally
          (re-frame.http.test-support/uninstall-managed-request-stubs!)
          (trace/unregister-listener! listener-id)
          (late-bind/set-fn! :router/dispatch! original))))))
