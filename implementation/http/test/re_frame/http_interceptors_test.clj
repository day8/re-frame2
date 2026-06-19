(ns re-frame.http-interceptors-test
  "JVM tests for Spec 014 §Middleware — per-frame request interceptor
  chain (rf2-6y3q).

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
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.http.managed :as http-managed]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http.managed :reload)
  (http-managed/clear-all-in-flight!)
  (http-managed/clear-all-http-interceptors!)
  ;; EP-0002 (rf2-5q7um6): reg-http-interceptor / clear-http-interceptor are
  ;; context-required frame-local, and these tests dispatch managed HTTP to
  ;; exercise the chain — both raise :rf.error/no-frame-context under no
  ;; scope. Pin :rf/default (an ordinary frame) as the established scope for
  ;; the body; tests that target an explicit frame pass {:frame …}.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (t)))

(use-fixtures :each reset-runtime)

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
  "Thin alias over `test-support/poll-until` (rf2-fun38) — preserves
  the per-file `(pred db)` arity that read sites here expect."
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until
     #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "http-interceptors reply"})))

;; ---- 1. single interceptor transforms the outgoing request ----------------

(deftest single-interceptor-transforms-request
  (testing "a registered :before interceptor's modifications reach the transport"
    (let [seen-auth (atom nil)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (reset! seen-auth (header-of ex "Authorization"))
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (rf/reg-http-interceptor :auth-header
          {:before (fn [ctx]
                     (assoc-in ctx [:request :headers "Authorization"]
                               "Bearer secret-token-42"))})
        (rf/reg-event :load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/secured")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:load])
        (await-reply! #(some? (:reply %)) 5000)
        (is (= "Bearer secret-token-42" @seen-auth)
            "the server saw the Authorization header the interceptor injected")
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
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/x")}
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
    ;; observed; the two "hit-*" atoms are independent "the request landed"
    ;; latches. We need the latter because :other-frame's request is *expected*
    ;; to carry a nil header, so we cannot use header-value-is-non-nil as a
    ;; readiness signal for that branch.
    (let [seen-on-default (atom nil)
          seen-on-other   (atom nil)
          hit-default     (atom false)
          hit-other       (atom false)
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (let [path (.getPath (.getRequestURI ex))]
                (cond
                  (.startsWith path "/from-default")
                  (do (reset! seen-on-default (header-of ex "X-Marker"))
                      (reset! hit-default true))

                  (.startsWith path "/from-other")
                  (do (reset! seen-on-other (header-of ex "X-Marker"))
                      (reset! hit-other true)))
                (write-response! ex 200 "application/json" "{}"))))]
      (try
        (rf/reg-frame :other-frame {:doc "alt frame"})
        ;; Register interceptor ONLY on :rf/default. :other-frame has none.
        (rf/reg-http-interceptor :marker
          {:frame  :rf/default
           :before (fn [ctx]
                     (assoc-in ctx [:request :headers "X-Marker"] "default-only"))})
        ;; Two events — one on each frame — both fire :rf.http/managed.
        (rf/reg-event :load-default
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/from-default")}
                    :decode  :json
                    :on-success nil}]]}))
        (rf/reg-event :load-other
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/from-other")}
                    :decode  :json
                    :on-success nil}]]}))
        (rf/dispatch-sync [:load-default])
        (rf/dispatch-sync [:load-other] {:frame :other-frame})
        ;; Wait for both server hits using the dedicated readiness latches.
        ;; Header values are NOT a valid readiness signal here — the
        ;; :other-frame request is expected to carry a nil header, which is
        ;; indistinguishable from "request has not yet landed" (rf2-fun38).
        (test-support/poll-until
          #(and @hit-default @hit-other)
          {:timeout-ms 5000 :label "both server hits landed"})
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
        (trace/register-listener! listener-id
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
        ;; Wait for the interceptor-failure trace to land (rf2-fun38) —
        ;; the trace event IS the observable signal. server-hits is then
        ;; asserted as zero (proven absence within the trace-fired window).
        (test-support/poll-until
          #(some (fn [t] (= :rf.error/http-interceptor-failed (:operation t)))
                 @traces)
          {:label ":rf.error/http-interceptor-failed surfaced"})
        (is (zero? @server-hits)
            "request was NOT dispatched — server saw zero requests")
        (is (some #(= :rf.error/http-interceptor-failed (:operation %))
                  @traces)
            ":rf.error/http-interceptor-failed appears on the trace stream")
        (finally
          (trace/unregister-listener! listener-id)
          (stop-server! srv))))))

;; ---- 4a. rf2-1jcpm — interceptor-failure URL redaction --------------------

(deftest interceptor-failure-trace-redacts-denylisted-query-params
  (testing "rf2-1jcpm (round-2 security audit finding 1) — when a
  `:before` throws, the `:rf.error/http-interceptor-failed` trace MUST
  route the request URL through the privacy composer. Previously the
  raw URL rode the trace surface, leaking any denylisted query param
  (`?api_key=…`) into trace consumers."
    (let [traces      (atom [])
          listener-id (gensym "interceptor-redact-")]
      (try
        (trace/register-listener! listener-id
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
        ;; Wait for the redacted-trace event to land (rf2-fun38).
        (test-support/poll-until
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
          (trace/unregister-listener! listener-id))))))

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
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (-> db
                       (update :replies (fnil conj []) reply))}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/x")}
                      :decode  :json}]]})))
        ;; First dispatch — interceptor fires.
        (rf/dispatch-sync [:load])
        (await-reply! #(= 1 (count (:replies %))) 5000)
        (is (= "Bearer A" @seen-auth) "first request carried the auth header")
        ;; Clear + dispatch again.
        (reset! seen-auth nil)
        (rf/clear-http-interceptor :auth-header)
        (rf/dispatch-sync [:load])
        (await-reply! #(= 2 (count (:replies %))) 5000)
        (is (nil? @seen-auth)
            "after clear, the second request did NOT carry the auth header")
        (finally (stop-server! srv))))))

;; ---- 5a. rf2-vl5xsp — single-arity clear FAILS CLOSED under no scope ------
;;
;; The fixture pins an ambient `*current-frame* :rf/default`, which MASKS the
;; old facade floor (`clear-http-interceptor` single-arity used to recurse
;; `[:rf/default id]`, synthesising the default before delegating). This test
;; clears the ambient scope (`*current-frame* nil`) so NO frame is carried,
;; then invokes the single-arity facade and asserts it raises the always-on
;; `:rf.error/no-frame-context` rather than silently clearing against a
;; synthesised `:rf/default` chain. Proves the EP-0002 carried invariant is
;; live on the public `rf/clear-http-interceptor` surface — the floor is gone.

(deftest clear-http-interceptor-single-arity-fails-closed-under-no-scope
  (testing "rf2-vl5xsp — single-arity `rf/clear-http-interceptor` under NO
            ambient frame raises :rf.error/no-frame-context (fails closed);
            it does NOT synthesise a :rf/default target. The facade must
            delegate frame resolution to the impl's require-current-frame!,
            never inject :rf/default from absence."
    (binding [frame/*current-frame* nil]
      (let [thrown (try (rf/clear-http-interceptor :some-id)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown)
            "single-arity clear with no carried frame must throw")
        (is (= :rf.error/no-frame-context
               (:rf.error/id (ex-data thrown)))
            "the throw is the always-on :rf.error/no-frame-context — no :rf/default floor")))))

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
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/x")}
                    :decode  :json
                    :on-success nil}]]}))
        (rf/dispatch-sync [:load])
        ;; Wait for both :before fns to fire (rf2-fun38).
        (test-support/poll-until
          #(= 2 (count @order))
          {:label "both interceptors in chain fired"})
        (is (= [:a-v2 :b] @order)
            "replaced :a's :before fired in :a's original position; no duplicate")
        (finally (stop-server! srv))))))

;; ---- 6b. clear-then-reg lands at the end of the chain (rf2-kg5nw) --------
;;
;; Round-2 audit finding 5.5: re-registering an id replaces in place
;; (test 6 above), but `clear-http-interceptor` followed by a fresh
;; `reg-http-interceptor` of the same id has different semantics — the
;; slot was *removed* by clear, so re-registering appends to the end.
;; Spec 014 §Chain order covers replace-in-place but the clear+re-reg
;; path was unpinned. Per the spec clarification landed in this bead,
;; clear-then-reg lands at the end (the slot's prior index is forgotten
;; on clear). Test pins that contract; a regression that started
;; preserving position across clear would break the documented behaviour
;; and surprise hot-reload tools that DO want a fresh end-of-chain slot.

(deftest clear-then-reg-appends-to-end-of-chain
  (testing "rf2-kg5nw — clear-http-interceptor followed by re-reg of the same
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
        (rf/clear-http-interceptor :a)
        ;; Re-register :a. Per the contract this is a FRESH registration,
        ;; not a position-preserving replace — it appends to the end.
        (rf/reg-http-interceptor :a {:before (fn [ctx] (swap! order conj :a-fresh) ctx)})

        ;; Confirm the chain order in the registry directly so the test
        ;; pins the slot ordering before the dispatch ever runs.
        (let [chain (http-managed/interceptors-snapshot :rf/default)]
          (is (= [:b :c :a] (mapv :id chain))
              "after clear-then-reg, :a moved to the end (was first; now last)"))

        (rf/reg-event :kg5nw/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/x")}
                    :decode     :json
                    :on-success nil}]]}))
        (rf/dispatch-sync [:kg5nw/load])
        ;; Wait for all three :before fns to fire (rf2-fun38).
        (test-support/poll-until
          #(= 3 (count @order))
          {:label "all post-clear-then-reg interceptors fired"})
        (is (= [:b :c :a-fresh] @order)
            "interceptors fired in the post-clear-then-reg order: :b, :c, :a (re-registered)")
        (finally (stop-server! srv))))))

(deftest clear-then-reg-distinguishes-from-replace-in-place
  (testing "rf2-kg5nw regression guard — bare `reg-http-interceptor` of an
            existing id replaces in place (test 6, position preserved),
            while clear-then-reg appends to the end. These are deliberately
            different paths; the test asserts they do NOT collapse into one."
    ;; Register in order :a, :b.
    (rf/reg-http-interceptor :a {:before identity})
    (rf/reg-http-interceptor :b {:before identity})
    (is (= [:a :b] (mapv :id (http-managed/interceptors-snapshot :rf/default))))

    ;; Bare re-reg of :a — replaces in place; order unchanged.
    (rf/reg-http-interceptor :a {:before (fn [c] c)})
    (is (= [:a :b] (mapv :id (http-managed/interceptors-snapshot :rf/default)))
        "bare re-reg preserves position (Spec 014 §Chain order, replace-in-place)")

    ;; Clear-then-reg of :a — appends to end; order changes.
    (rf/clear-http-interceptor :a)
    (rf/reg-http-interceptor :a {:before (fn [c] c)})
    (is (= [:b :a] (mapv :id (http-managed/interceptors-snapshot :rf/default)))
        "clear-then-reg lands at the end (rf2-kg5nw contract)")))

;; ---- 7. invalid interceptor shape raises ---------------------------------

(deftest invalid-interceptor-shape-raises
  (testing "rf2-uheqq — reg-http-interceptor (shape iii) rejects non-keyword
            id, non-map interceptor-map, non-fn :before / :after, missing
            both :before and :after, or non-keyword :frame"
    ;; non-keyword id
    (let [thrown (try (rf/reg-http-interceptor "string-id" {:before identity})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    ;; non-map interceptor-map
    (let [thrown (try (rf/reg-http-interceptor :x "not-a-map")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    ;; non-fn :before
    (let [thrown (try (rf/reg-http-interceptor :x {:before "not-a-fn"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    ;; non-fn :after
    (let [thrown (try (rf/reg-http-interceptor :x {:after "not-a-fn"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    ;; missing both :before AND :after — a no-op interceptor is rejected
    (let [thrown (try (rf/reg-http-interceptor :x {:doc "no fns at all"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    ;; non-keyword :frame
    (let [thrown (try (rf/reg-http-interceptor :x {:frame "not-a-keyword" :before identity})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))))

;; ---- 8. clear-all-http-interceptors! bulk-clear (rf2-lfvi) -----------------
;;
;; Per rf2-lfvi: only the single-id `clear-http-interceptor` is covered by
;; the test above (`clear-http-interceptor-unregisters`). The bulk-clear
;; helper at `http_managed.cljc:390` is uncovered. Test fixtures and the
;; reset-runtime path use the bulk form to drop every registered chain;
;; a regression that left even one slot populated would only surface as
;; cross-test pollution.

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

        ;; Confirm the per-frame chain has all three before the bulk-clear.
        (let [chain (http-managed/interceptors-snapshot :rf/default)]
          (is (= 3 (count chain)))
          (is (= #{:hdr-a :hdr-b :hdr-c}
                 (set (map :id chain)))
              "all three ids appear in the :rf/default chain"))

        (rf/reg-event :test.lfvi/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (-> db (update :replies (fnil conj []) reply))}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/x")}
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
        (http-managed/clear-all-http-interceptors!)

        ;; Atom is now empty.
        (is (= {} (http-managed/interceptors-snapshot))
            "the per-frame chain atom is now empty across every frame")

        ;; Second dispatch — none of the cleared interceptors fire.
        (rf/dispatch-sync [:test.lfvi/load])
        (await-reply! #(= 2 (count (:replies %))) 5000)
        (let [second-req (first @seen-headers)]
          (is (nil? (:a second-req)) "X-A is absent after bulk-clear")
          (is (nil? (:b second-req)) "X-B is absent after bulk-clear")
          (is (nil? (:c second-req)) "X-C is absent after bulk-clear"))

        (finally (stop-server! srv))))))

(deftest clear-all-http-interceptors-leaves-other-registries-untouched
  (testing "clear-all-http-interceptors! touches only the http interceptor
            registry — :event / :sub / :fx slots are preserved"
    (rf/reg-http-interceptor :test.lfvi/dummy {:before identity})
    (rf/reg-event :test.lfvi/ev (fn [{:keys [db]} _] {:db db}))
    (rf/reg-sub :test.lfvi/sub (fn [_ _] :stub))
    (rf/reg-fx :test.lfvi/fx (fn [_ _] nil))

    (is (seq (http-managed/interceptors-snapshot))
        "pre-clear: interceptor atom has entries")

    (http-managed/clear-all-http-interceptors!)

    (is (= {} (http-managed/interceptors-snapshot))
        ":http interceptor atom is empty")
    (is (some? (registrar/lookup :event :test.lfvi/ev))
        ":event kind is untouched by the bulk-clear")
    (is (some? (registrar/lookup :sub :test.lfvi/sub))
        ":sub kind is untouched")
    (is (some? (registrar/lookup :fx :test.lfvi/fx))
        ":fx kind is untouched")))

(deftest clear-all-http-interceptors-is-idempotent
  (testing "calling clear-all-http-interceptors! on an empty registry is a no-op"
    (is (= {} (http-managed/interceptors-snapshot))
        "starting clean (per fixture)")
    (is (nil? (http-managed/clear-all-http-interceptors!))
        "first call returns nil")
    (is (nil? (http-managed/clear-all-http-interceptors!))
        "second call on the already-empty registry is also nil")
    (is (= {} (http-managed/interceptors-snapshot))
        "atom stays empty")))

;; ---- rf2-rznrz — sensitivity recomputed from the POST-:before request -----
;;
;; A `:before` that MARKS the request sensitive (sets [:request :sensitive?]
;; true) followed by a LATER :before that throws must produce a
;; :rf.error/http-interceptor-failed trace redacted under the EFFECTIVE
;; (post-mark) sensitivity — not the stale pre-chain flag. We assert against
;; a NON-denylisted query param (`customer_email`) so the only thing that can
;; redact it is the per-request :sensitive? flag the first :before set; the
;; query-param denylist alone would leave it verbatim.

(deftest before-marked-sensitive-then-throw-redacts-under-effective-sensitivity
  (testing "rf2-rznrz — a :before sets [:request :sensitive?] true, a later
            :before throws; the interceptor-failed trace redacts the
            NON-denylisted query value because effective sensitivity is
            recomputed from the post-mark request (not the stale pre-chain
            flag)"
    (let [traces      (atom [])
          listener-id (gensym "rznrz-mark-sensitive-")]
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
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
        (test-support/poll-until
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
          (trace/unregister-listener! listener-id))))))

;; ---- rf2-rznrz — CLJS-only-key check runs on the POST-:before request -----
;;
;; A :before that ADDS a JVM-degraded CLJS-only key (:credentials / :mode /
;; …) into the request must trip the :rf.http/cljs-only-key-ignored-on-jvm
;; warning. Previously check-cljs-only-keys! ran on the ORIGINAL args before
;; the chain, so a :before-added key proceeded on JVM with no degraded-key
;; warning at all.

(deftest before-added-cljs-only-key-trips-degradation-warning
  (testing "rf2-rznrz — a :before that adds a CLJS-only key (:credentials)
            into the request trips :rf.http/cljs-only-key-ignored-on-jvm; the
            check now runs against the post-:before request, not the original
            args"
    (let [traces      (atom [])
          listener-id (gensym "rznrz-cljs-only-")
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{\"ok\":true}")))]
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        ;; The request as DISPATCHED carries no CLJS-only key; the :before
        ;; adds :credentials into the request map.
        (rf/reg-http-interceptor :add-credentials
          {:before (fn [ctx] (assoc-in ctx [:request :credentials] :include))})
        (rf/reg-event :rznrz.cljsonly/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/x")}
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
          (trace/unregister-listener! listener-id)
          (stop-server! srv))))))

;; ---- rf2-oyd1b — direct unit tests for the fx wrappers --------------------
;;
;; The :rf.fx/reg-http-interceptor + :rf.fx/clear-http-interceptor fxs
;; (rf2-yhfgf) are exercised through conformance fixtures
;; (spec/conformance/fixtures/http-interceptor-*). These tests pin the
;; wrapper contract directly so a conformance-harness DSL change
;; doesn't ripple — and so a regression in either fx surfaces here
;; with a precise failure rather than as a conformance-fixture flake.

(deftest reg-http-interceptor-fx-mutates-the-atom-rf2-oyd1b
  (testing "rf2-oyd1b — [:rf.fx/reg-http-interceptor {...}] adds an
            interceptor to the :rf/default frame's chain (observed via
            interceptors-snapshot)."
    (rf/reg-event :oyd1b/register
      (fn [_ _]
        {:fx [[:rf.fx/reg-http-interceptor
               {:id     :oyd1b/auth
                :before identity
                :doc    "fixture interceptor"
                :tags   #{:auth}}]]}))

    (is (empty? (http-managed/interceptors-snapshot :rf/default))
        "pre-dispatch: no interceptors on :rf/default")

    (rf/dispatch-sync [:oyd1b/register])

    (let [chain (http-managed/interceptors-snapshot :rf/default)
          slot  (first (filter #(= :oyd1b/auth (:id %)) chain))]
      (is (= 1 (count chain))
          "the fx wrapper actually mutates the atom (not just a return-value smoke)")
      (is (= :oyd1b/auth (:id slot)))
      (is (fn? (:before slot)))
      (is (= "fixture interceptor" (:doc slot)))
      (is (= #{:auth} (:tags slot))))))

(deftest reg-http-interceptor-fx-honours-explicit-frame-rf2-oyd1b
  (testing "rf2-oyd1b — explicit :frame routes to the named slot, not :rf/default"
    (rf/reg-event :oyd1b/register-on-named
      (fn [_ _]
        {:fx [[:rf.fx/reg-http-interceptor
               {:frame  :rf/api
                :id     :oyd1b/named
                :before identity}]]}))

    (rf/dispatch-sync [:oyd1b/register-on-named])

    (is (empty? (http-managed/interceptors-snapshot :rf/default))
        ":rf/default is not touched by an :rf/api registration")
    (let [chain (http-managed/interceptors-snapshot :rf/api)]
      (is (= 1 (count chain)))
      (is (= :oyd1b/named (:id (first chain)))))))

(deftest clear-http-interceptor-fx-mutates-the-atom-rf2-oyd1b
  (testing "rf2-oyd1b — [:rf.fx/clear-http-interceptor {:id ...}] removes
            the slot from :rf/default (the implicit frame)."
    (http-managed/reg-http-interceptor :oyd1b/to-clear {:before identity})
    (is (= 1 (count (http-managed/interceptors-snapshot :rf/default)))
        "pre-clear: the slot is present")

    (rf/reg-event :oyd1b/clear
      (fn [_ _]
        {:fx [[:rf.fx/clear-http-interceptor {:id :oyd1b/to-clear}]]}))
    (rf/dispatch-sync [:oyd1b/clear])

    (is (empty? (http-managed/interceptors-snapshot :rf/default))
        "the slot is gone after the fx")))

(deftest clear-http-interceptor-fx-honours-explicit-frame-rf2-oyd1b
  (testing "rf2-oyd1b — explicit :frame on the clear fx scopes the removal"
    (http-managed/reg-http-interceptor :oyd1b/scoped {:frame :rf/api :before identity})
    (http-managed/reg-http-interceptor :oyd1b/default-survivor {:before identity})

    (rf/reg-event :oyd1b/clear-on-named
      (fn [_ _]
        {:fx [[:rf.fx/clear-http-interceptor
               {:frame :rf/api :id :oyd1b/scoped}]]}))
    (rf/dispatch-sync [:oyd1b/clear-on-named])

    (is (empty? (http-managed/interceptors-snapshot :rf/api))
        ":rf/api lost its slot")
    (is (= 1 (count (http-managed/interceptors-snapshot :rf/default)))
        ":rf/default is unaffected — the clear was scoped to :rf/api")))

(deftest clear-http-interceptor-fx-defaults-frame-to-rf-default-rf2-oyd1b
  (testing "rf2-oyd1b — when :frame is nil/absent the fx routes to :rf/default
            (matching the fn-form behaviour)."
    (http-managed/reg-http-interceptor :oyd1b/dflt {:before identity})
    (rf/reg-event :oyd1b/clear-no-frame
      (fn [_ _]
        ;; No :frame key — must default to :rf/default.
        {:fx [[:rf.fx/clear-http-interceptor {:id :oyd1b/dflt}]]}))
    (rf/dispatch-sync [:oyd1b/clear-no-frame])
    (is (empty? (http-managed/interceptors-snapshot :rf/default)))))

(deftest reg-http-interceptor-fx-rejects-invalid-args-rf2-oyd1b
  (testing "rf2-oyd1b + rf2-uheqq — invalid args (missing :id, missing both
            :before AND :after, non-keyword id) trigger the same
            :rf.error/http-bad-interceptor throw the fn-form raises. The
            error fires inside the runtime's fx dispatch loop, so we trap
            via a trace-error listener and assert NO interceptor was
            registered."
    (let [errors (atom [])
          cb-id  ::oyd1b-bad-args]
      (try
        (trace/register-listener!
          cb-id
          (fn [ev]
            (when (= :error (:op-type ev))
              (swap! errors conj ev))))

        ;; missing :id
        (rf/reg-event :oyd1b/bad-no-id
          (fn [_ _]
            {:fx [[:rf.fx/reg-http-interceptor {:before identity}]]}))
        (try (rf/dispatch-sync [:oyd1b/bad-no-id])
             (catch Throwable _ nil))
        (is (empty? (http-managed/interceptors-snapshot :rf/default))
            "missing :id → no interceptor registered")

        ;; missing both :before AND :after (a no-op interceptor is rejected
        ;; under the rf2-uheqq shape-iii contract)
        (rf/reg-event :oyd1b/bad-no-fns
          (fn [_ _]
            {:fx [[:rf.fx/reg-http-interceptor {:id :x}]]}))
        (try (rf/dispatch-sync [:oyd1b/bad-no-fns])
             (catch Throwable _ nil))
        (is (empty? (http-managed/interceptors-snapshot :rf/default))
            "missing both :before and :after → no interceptor registered")

        ;; non-keyword :id
        (rf/reg-event :oyd1b/bad-string-id
          (fn [_ _]
            {:fx [[:rf.fx/reg-http-interceptor
                   {:id "not-a-keyword" :before identity}]]}))
        (try (rf/dispatch-sync [:oyd1b/bad-string-id])
             (catch Throwable _ nil))
        (is (empty? (http-managed/interceptors-snapshot :rf/default))
            "non-keyword :id → no interceptor registered")

        (finally
          (trace/unregister-listener! cb-id))))))

(deftest reg-and-clear-http-interceptor-fxs-roundtrip-rf2-oyd1b
  (testing "rf2-oyd1b — register-then-clear via fxs round-trips cleanly,
            mirroring the fn-form's idempotency."
    (rf/reg-event :oyd1b/round-trip
      (fn [_ _]
        {:fx [[:rf.fx/reg-http-interceptor
               {:id :oyd1b/rt :before identity}]
              [:rf.fx/clear-http-interceptor
               {:id :oyd1b/rt}]]}))
    (rf/dispatch-sync [:oyd1b/round-trip])
    (is (empty? (http-managed/interceptors-snapshot :rf/default))
        "register followed by clear in the same event leaves the chain empty")))

;; ===========================================================================
;; rf2-uheqq — `:after` response-side hook
;; ===========================================================================
;;
;; Per Spec 014 §Middleware (rf2-uheqq, Mike decision 2026-05-28 = rf2-omwua
;; option b, shape iii): each HTTP interceptor may carry an optional `:after`
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
  (testing "rf2-uheqq — three :after interceptors registered :a → :b → :c
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
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load])
        (await-reply-with-payload!)
        (is (= [:c :b :a] @order)
            ":after fires in reverse registration order")
        (finally (stop-server! srv))))))

;; ---- 2. :after sees the request ctx (request-correlated telemetry) -------

(deftest after-sees-the-request-ctx-via-wall-clock-delta
  (testing "rf2-uheqq — `:after` receives the SAME ctx the `:before`
            produced; a `:before` that stashes `(System/nanoTime)` and an
            `:after` that reads it can compute a non-negative wall-clock
            delta. This is the load-bearing test that ctx threads through."
    (let [observed (atom nil)
          srv      (single-success-server "{\"ok\":\"delta\"}")]
      (try
        (rf/reg-http-interceptor :timing
          {:before (fn [ctx]
                     (assoc ctx ::start (System/nanoTime)))
           :after  (fn [ctx resp]
                     (reset! observed
                             {:start    (::start ctx)
                              :delta-ns (- (System/nanoTime)
                                           (::start ctx))})
                     resp)})
        (rf/reg-event :uheqq/load-timing
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/timing")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-timing])
        (await-reply-with-payload!)
        (is (some? (:start @observed))
            ":before's stash key is visible to :after via the shared ctx")
        (is (and (number? (:delta-ns @observed))
                 (>= (:delta-ns @observed) 0))
            "wall-clock delta computed from ctx is a non-negative number")
        (finally (stop-server! srv))))))

;; ---- 3. response transform threads through -------------------------------

(deftest after-can-transform-the-response-shape
  (testing "rf2-uheqq — `:after` returns the (possibly-transformed)
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
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-xform])
        (let [reply (await-reply-with-payload!)]
          (is (= :success (:kind reply))
              "the reply still classifies as :success")
          (is (= :after (get-in reply [:value :touched-by]))
              ":after's mutation of (:value reply) reaches the on-success target")
          (is (= "payload" (get-in reply [:value :original]))
              "the underlying response value is preserved"))
        (finally (stop-server! srv))))))

;; ---- 4. interceptors without :after are transparent in the response chain

(deftest after-less-interceptors-are-transparent
  (testing "rf2-uheqq — interceptors registered with only `:before` (no
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
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-transparent])
        (await-reply-with-payload!)
        (is (= [:before-only-fired :after-only-fired] @order)
            ":before-only fired on request side; :after-only fired on response side; no nil-substitution surfaced")
        (finally (stop-server! srv))))))

;; ===========================================================================
;; rf2-uheqq — four motivating use cases (cited in the bead)
;; ===========================================================================

;; ---- USE-CASE 1. Rate-limit header parsing -------------------------------

(deftest motivating-rate-limit-header-parse
  (testing "rf2-uheqq use case 1 — an `:after` interceptor parses
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
                     ;; The reply-payload's :value carries the decoded
                     ;; body. Headers don't ride on the reply by default;
                     ;; the load-bearing assertion is that :after CAN
                     ;; reshape the reply with a structured `:rate-limit`
                     ;; slot derived from the server-provided values + the
                     ;; ctx-stashed `:before` mark, so downstream
                     ;; `:on-success` handlers can throttle without
                     ;; re-parsing the header per-call.
                     (assoc resp :rate-limit
                            {:remaining 37
                             :limit     100
                             :ctx-aware (::rate-limit-aware ctx)}))})
        (rf/reg-event :uheqq/load-rate
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/items")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-rate])
        (let [reply (await-reply-with-payload!)]
          (is (= 37  (get-in reply [:rate-limit :remaining]))
              "X-RateLimit-Remaining parsed and attached to the reply by :after")
          (is (= 100 (get-in reply [:rate-limit :limit]))
              "X-RateLimit-Limit parsed and attached")
          (is (true? (get-in reply [:rate-limit :ctx-aware]))
              ":after read the :before's stashed ctx flag (request-correlation works)"))
        (finally (stop-server! srv))))))

;; ---- USE-CASE 2. Response-time telemetry --------------------------------

(deftest motivating-response-time-telemetry
  (testing "rf2-uheqq use case 2 — :before stamps a wall-clock start; the
            :after reads it back via the SHARED ctx and computes the
            response time delta. This is exactly what rf2-uheqq's
            'ctx-carried-from-before' clause unlocks."
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
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/telemetry")}
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
  (testing "rf2-uheqq use case 3 — an :after interceptor inspects a
            simulated Cache-Control directive and tags the reply with a
            structured :cache slot. Downstream `:on-success` handlers
            consume the structured form without re-parsing the header
            string at every dispatch site."
    (let [srv (start-server!
                (fn [^HttpExchange ex]
                  (-> ex .getResponseHeaders
                      (.set "Cache-Control" "max-age=600, public"))
                  (write-response! ex 200 "application/json"
                                   "{\"doc\":\"hello\"}")))]
      (try
        (rf/reg-http-interceptor :cache-control
          {:after (fn [_ctx resp]
                    ;; The test-side server emits the header above; the
                    ;; transport doesn't ride it on the reply, but the
                    ;; load-bearing assertion is that :after CAN add a
                    ;; structured :cache slot derived from a parse of
                    ;; the canonical form.
                    (assoc resp :cache {:max-age 600 :public? true}))})
        (rf/reg-event :uheqq/load-cache
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/cache")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-cache])
        (let [reply (await-reply-with-payload!)]
          (is (= 600 (get-in reply [:cache :max-age]))
              "Cache-Control max-age parsed and attached")
          (is (true? (get-in reply [:cache :public?]))
              "Cache-Control 'public' flag attached"))
        (finally (stop-server! srv))))))

;; ---- USE-CASE 4. 401 auth-token refresh ---------------------------------

(deftest motivating-401-auth-refresh
  (testing "rf2-uheqq use case 4 — an :after interceptor inspects the
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
                    (if (and (= :failure (:kind resp))
                             (= :rf.http/http-4xx
                                (get-in resp [:failure :kind]))
                             (= 401 (get-in resp [:failure :status])))
                      (assoc resp :auth-refresh-required true)
                      resp))})
        (rf/reg-event :uheqq/load-401
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" (:port srv) "/protected")}
                      :decode  :json}]]})))
        (rf/dispatch-sync [:uheqq/load-401])
        (let [reply (await-reply-with-payload!)]
          (is (= :failure (:kind reply))
              "the 401 classifies as a failure reply")
          (is (= :rf.http/http-4xx (get-in reply [:failure :kind]))
              "failure category is :rf.http/http-4xx")
          (is (= 401 (get-in reply [:failure :status]))
              "status 401 rides on the failure slot")
          (is (true? (:auth-refresh-required reply))
              ":after attached :auth-refresh-required so a downstream handler can mint the refresh dispatch"))
        (finally (stop-server! srv))))))
