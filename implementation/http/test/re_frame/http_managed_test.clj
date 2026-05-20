(ns re-frame.http-managed-test
  "JVM smoke tests for Spec 014 — `:rf.http/managed`.

  Covers the canned-stub fxs (no real network IO needed for the stubs)
  AND, where in-process HTTP is convenient, the real
  `java.net.http.HttpClient`-backed transport against a tiny
  com.sun.net.httpserver test server.

  Per Spec 014 §Implementation status — JVM transport is part of the
  CLJS reference implementation's claim."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.http-decode :as http-decode]
            [re-frame.http-encoding :as http-encoding]
            [re-frame.http-managed :as http-managed]
            ;; rf2-cdmle — the canned-stub fxs no longer register at
            ;; `re-frame.http-managed` load time. This test file uses
            ;; `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}`
            ;; throughout, so it opts in by requiring the test-support ns.
            ;; Loading registers `:rf.http/managed-canned-success` and
            ;; `:rf.http/managed-canned-failure` against the same handler
            ;; bodies the earlier `(when interop/debug-enabled? ...)` gate
            ;; wired up.
            [re-frame.http-test-support]
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
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http-managed :reload)
  ;; rf2-cdmle — clear-all! above wipes the canned-stub fx registrations
  ;; that re-frame.http-test-support put into the registrar at first
  ;; load. Reload the test-support ns so its registration body fires
  ;; again for the next test (mirrors the http-managed reload above).
  (require 're-frame.http-test-support :reload)
  ((requiring-resolve 're-frame.machines/reset-timers!))
  (http-managed/clear-all-in-flight!)
  (t))

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
  `(rf/get-frame-db :rf/default)`. Returns the final db on success;
  throws `:rf.test/poll-timeout` on timeout. Thin alias over
  `test-support/poll-until` (rf2-fun38) — preserves the per-file
  `db`-closing-arity shape that read sites here expect."
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until
     #(let [db (rf/get-frame-db :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "http-managed reply"})))

;; ---- 1. canned-success: round-trip default reply addressing ---------------

(deftest canned-success-default-reply-addressing
  (testing "the canned-success stub dispatches a default reply (originating event-id with :rf/reply)"
    (rf/reg-event-fx :article/load
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
    (rf/reg-event-fx :auth/login
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request   {:method :post :url "/auth/login"}
                :on-failure [:auth/login-error]}]]}))
    (rf/reg-event-db :auth/login-error
      (fn [db [_ payload]]
        (assoc db :auth-error payload)))
    (rf/dispatch-sync [:auth/login]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (await-reply! #(some? (:auth-error %)))]
      (is (= :failure (get-in db [:auth-error :kind])))
      (is (= :rf.http/transport (get-in db [:auth-error :failure :kind]))))))

;; ---- 3. silenced reply (on-success nil) -----------------------------------

(deftest silenced-reply-on-success-nil
  (testing "explicit :on-success nil swallows the reply silently"
    (let [seen (atom 0)]
      (rf/reg-event-fx :ping
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

;; ---- 4. real JVM transport: GET success -----------------------------------

(deftest jvm-real-get-success
  (testing "java.net.http.HttpClient transport — GET, JSON decode, default reply"
    (let [{:keys [server port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json"
                               "{\"article\":{\"title\":\"hello\",\"id\":42}}")))]
      (try
        (rf/reg-event-fx :article/load
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
        (rf/reg-event-fx :article/load
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
        (rf/reg-event-fx :page/load
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
        (rf/reg-event-fx :page/load
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
                         :decode           :auto
                         :decode-supplied? false
                         :request-id       :test/req
                         :url              "/test"
                         :sensitive?       false})]
          (is (= {:ok true} decoded)
              (str "non-canonical Content-Type casing " (pr-str ct-key)
                   " must sniff to :json, not :blob")))))))

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
        (rf/reg-event-fx :flaky/load
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
        (rf/reg-event-fx :recover/load
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
    (rf/reg-event-fx :load
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
        (trace/register-trace-listener! listener-id
                                  (fn [ev] (swap! traces conj ev)))
        ;; If a reply ever dispatches the test will catch it (silently
        ;; ignored handler) — but the load-bearing assertion is that no
        ;; throw escapes the abort fx.
        (rf/reg-event-fx :kdwnq/abort-never-issued
          (fn [_ _]
            {:fx [[:rf.http/managed-abort :kdwnq/never-issued]]}))
        (rf/reg-event-db :kdwnq/some-reply
          (fn [db _]
            (reset! reply-fired? true)
            db))
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
          (trace/unregister-trace-listener! listener-id))))))

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
        (rf/reg-event-fx :slow/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                      :request-id :slow
                      :decode     :json}]]})))
        (rf/reg-event-fx :slow/abort
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
        (rf/reg-event-fx :on7sj/load
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
        (rf/reg-event-fx :on7sj/abort
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
        (rf/reg-event-fx :upload
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
  (testing "with-managed-request-stubs routes :method+:url to the configured reply"
    (rf/reg-event-fx :articles/list
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :result reply)}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs
      {[:get "/articles"] {:reply {:ok [:hello :world]}}}
      (rf/dispatch-sync [:articles/list]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
      (let [db (await-reply! #(some? (:result %)) 2000)]
        (is (= :success (get-in db [:result :kind])))
        (is (= [:hello :world] (get-in db [:result :value])))))))

;; ---- 11b. canned-stub fxs gated on explicit test-support require (rf2-cdmle)
;;
;; Per rf2-cdmle (follow-up to rf2-zk08x): the gate that decides whether
;; the canned-stub fxs (`:rf.http/managed-canned-success` /
;; `:rf.http/managed-canned-failure`) register moved from
;; `(when interop/debug-enabled? ...)` inside `re-frame.http-managed` to
;; the require boundary itself. The fxs now register under
;; `re-frame.http-test-support`; production code paths must not require
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
;; `re-frame.http-test-support :reload` between tests, so the canned
;; stubs ARE registered for the bulk of the suite (the methodology
;; check below pins that). The standalone negative-assertion test that
;; exercises the absence path (test-support absent → canned stubs
;; absent) lives in `re-frame.http-test-support-absent-test` so a
;; sibling `:require` in this ns can't reintroduce the fxs and false-
;; pass the absence assertion.

(deftest canned-stub-fxs-registered-when-test-support-required
  (testing "rf2-cdmle methodology check — with re-frame.http-test-support
            in the require closure (this ns requires it at the top), the
            two canonical canned-stub fxs MUST be registered. The
            absence test in re-frame.http-test-support-absent-test would
            be vacuous if this side did not actually register the stubs."
    ;; The fixture has just reloaded http-test-support, so the canned
    ;; stubs are present. The production-eligible fxs are present too.
    (is (some? (registrar/lookup :fx :rf.http/managed))
        ":rf.http/managed is dev+prod — always registered by re-frame.http-managed")
    (is (some? (registrar/lookup :fx :rf.http/managed-abort))
        ":rf.http/managed-abort is dev+prod — always registered by re-frame.http-managed")
    (is (some? (registrar/lookup :fx :rf.http/managed-canned-success))
        ":rf.http/managed-canned-success registered when re-frame.http-test-support is required")
    (is (some? (registrar/lookup :fx :rf.http/managed-canned-failure))
        ":rf.http/managed-canned-failure registered when re-frame.http-test-support is required")))

;; ---- 12. decode reflection metadata ---------------------------------------

(deftest decode-reflection-metadata
  (testing ":rf.http/decode-schemas declared on the handler is queryable via handler-meta"
    (rf/reg-event-fx :article/load
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
  on success; throws `:rf.test/poll-timeout` on timeout. Thin alias
  over `test-support/poll-until` (rf2-fun38)."
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
            (fn [data _]
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
        (rf/reg-event-fx :slow/load
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
;; consumers wanting abort telemetry subscribe via `register-trace-listener!`.

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
        (rf/reg-event-fx :search/run
          (fn [_ [_ q]]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q?" q)}
                    :request-id :search
                    :decode     :json
                    ;; Silence success on the prior request — only the
                    ;; supersede-driven :on-failure dispatch is under test.
                    :on-success nil
                    :on-failure [:search/a-failed]}]]}))
        (rf/reg-event-fx :search/run-superseding
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q?fresh")}
                    :request-id :search
                    :decode     :json
                    :on-failure nil
                    :on-success [:search/b-ok]}]]}))
        (rf/reg-event-db :search/a-failed (fn [db _] (reset! a-failed? true) db))
        (rf/reg-event-db :search/b-ok     (fn [db _] (reset! b-success? true) db))

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
            :reason :request-id-superseded so register-trace-listener! consumers
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
        (trace/register-trace-listener! cb-id
                                  (fn [ev]
                                    (when (= :rf.http/aborted (:operation ev))
                                      (swap! events conj ev))))
        (rf/reg-event-fx :search/run
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/q1")}
                    :request-id :search
                    :decode     :json}]]}))
        (rf/reg-event-fx :search/run-superseding
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
          (trace/unregister-trace-listener! cb-id)
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
        (rf/reg-event-fx :slow/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                    :request-id :slow
                    :decode     :json
                    :on-failure [:slow/failed]}]]}))
        (rf/reg-event-fx :slow/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :slow]]}))
        (rf/reg-event-db :slow/failed
          (fn [db [_ payload]]
            (reset! reply-fired? true)
            (reset! reply-data payload)
            db))

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
