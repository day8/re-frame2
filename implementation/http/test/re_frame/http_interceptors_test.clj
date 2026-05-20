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
            [re-frame.http-managed :as http-managed]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http-managed :reload)
  (http-managed/clear-all-in-flight!)
  (http-managed/clear-all-http-interceptors!)
  (t))

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
     #(let [db (rf/get-frame-db :rf/default)] (when (pred db) db))
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
          (fn [ctx]
            (assoc-in ctx [:request :headers "Authorization"]
                      "Bearer secret-token-42")))
        (rf/reg-event-fx :load
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
          (fn [ctx]
            (swap! order conj :first)
            (assoc-in ctx [:request :headers "X-One"] "1")))
        (rf/reg-http-interceptor :second
          (fn [ctx]
            (swap! order conj :second)
            ;; verify earlier interceptor's output is visible
            (is (= "1" (get-in ctx [:request :headers "X-One"])))
            (assoc-in ctx [:request :headers "X-Two"] "2")))
        (rf/reg-http-interceptor :third
          (fn [ctx]
            (swap! order conj :third)
            (is (= "2" (get-in ctx [:request :headers "X-Two"])))
            (assoc-in ctx [:request :headers "X-Three"] "3")))
        (rf/reg-event-fx :load
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
          {:frame :rf/default}
          (fn [ctx]
            (assoc-in ctx [:request :headers "X-Marker"] "default-only")))
        ;; Two events — one on each frame — both fire :rf.http/managed.
        (rf/reg-event-fx :load-default
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request {:url (str "http://127.0.0.1:" port "/from-default")}
                    :decode  :json
                    :on-success nil}]]}))
        (rf/reg-event-fx :load-other
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
          (fn [_ctx]
            (throw (ex-info "kaboom" {:detail :synthetic}))))
        (rf/reg-event-fx :load
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
          (fn [_ctx]
            (throw (ex-info "kaboom" {:detail :synthetic}))))
        (rf/reg-event-fx :load
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
          (fn [ctx]
            (assoc-in ctx [:request :headers "Authorization"]
                      "Bearer A")))
        (rf/reg-event-fx :load
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

;; ---- 6. re-registering an id replaces in place ---------------------------

(deftest re-registering-id-replaces-slot
  (testing "re-registering an id swaps the :before in the existing position; no duplicates"
    (let [order (atom [])
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-response! ex 200 "application/json" "{}")))]
      (try
        (rf/reg-http-interceptor :a (fn [ctx] (swap! order conj :a-v1) ctx))
        (rf/reg-http-interceptor :b (fn [ctx] (swap! order conj :b)    ctx))
        ;; Replace :a — should keep its position (first), not append.
        (rf/reg-http-interceptor :a (fn [ctx] (swap! order conj :a-v2) ctx))
        (rf/reg-event-fx :load
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
        (rf/reg-http-interceptor :a (fn [ctx] (swap! order conj :a) ctx))
        (rf/reg-http-interceptor :b (fn [ctx] (swap! order conj :b) ctx))
        (rf/reg-http-interceptor :c (fn [ctx] (swap! order conj :c) ctx))

        ;; Clear :a — slot is removed entirely.
        (rf/clear-http-interceptor :a)
        ;; Re-register :a. Per the contract this is a FRESH registration,
        ;; not a position-preserving replace — it appends to the end.
        (rf/reg-http-interceptor :a (fn [ctx] (swap! order conj :a-fresh) ctx))

        ;; Confirm the chain order in the registry directly so the test
        ;; pins the slot ordering before the dispatch ever runs.
        (let [chain (get @http-managed/interceptors :rf/default)]
          (is (= [:b :c :a] (mapv :id chain))
              "after clear-then-reg, :a moved to the end (was first; now last)"))

        (rf/reg-event-fx :kg5nw/load
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
    (rf/reg-http-interceptor :a identity)
    (rf/reg-http-interceptor :b identity)
    (is (= [:a :b] (mapv :id (get @http-managed/interceptors :rf/default))))

    ;; Bare re-reg of :a — replaces in place; order unchanged.
    (rf/reg-http-interceptor :a (fn [c] c))
    (is (= [:a :b] (mapv :id (get @http-managed/interceptors :rf/default)))
        "bare re-reg preserves position (Spec 014 §Chain order, replace-in-place)")

    ;; Clear-then-reg of :a — appends to end; order changes.
    (rf/clear-http-interceptor :a)
    (rf/reg-http-interceptor :a (fn [c] c))
    (is (= [:b :a] (mapv :id (get @http-managed/interceptors :rf/default)))
        "clear-then-reg lands at the end (rf2-kg5nw contract)")))

;; ---- 7. invalid interceptor shape raises ---------------------------------

(deftest invalid-interceptor-shape-raises
  (testing "reg-http-interceptor with a non-keyword id / non-fn before / non-map opts throws :rf.error/http-bad-interceptor"
    ;; non-keyword id
    (let [thrown (try (rf/reg-http-interceptor "string-id" identity)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.getMessage thrown))))
    ;; non-fn before
    (let [thrown (try (rf/reg-http-interceptor :x "not-a-fn")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.getMessage thrown))))
    ;; non-map opts in three-arity
    (let [thrown (try (rf/reg-http-interceptor :x "not-a-map" identity)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.getMessage thrown))))
    ;; non-keyword :frame in opts
    (let [thrown (try (rf/reg-http-interceptor :x {:frame "not-a-keyword"} identity)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.getMessage thrown))))))

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
          (fn [ctx] (assoc-in ctx [:request :headers "X-A"] "1")))
        (rf/reg-http-interceptor :hdr-b
          (fn [ctx] (assoc-in ctx [:request :headers "X-B"] "2")))
        (rf/reg-http-interceptor :hdr-c
          (fn [ctx] (assoc-in ctx [:request :headers "X-C"] "3")))

        ;; Confirm the per-frame chain has all three before the bulk-clear.
        (let [chain (get @http-managed/interceptors :rf/default)]
          (is (= 3 (count chain)))
          (is (= #{:hdr-a :hdr-b :hdr-c}
                 (set (map :id chain)))
              "all three ids appear in the :rf/default chain"))

        (rf/reg-event-fx :test.lfvi/load
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
        (is (= {} @http-managed/interceptors)
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
    (rf/reg-http-interceptor :test.lfvi/dummy identity)
    (rf/reg-event-db :test.lfvi/ev (fn [db _] db))
    (rf/reg-sub :test.lfvi/sub (fn [_ _] :stub))
    (rf/reg-fx :test.lfvi/fx (fn [_ _] nil))

    (is (seq @http-managed/interceptors)
        "pre-clear: interceptor atom has entries")

    (http-managed/clear-all-http-interceptors!)

    (is (= {} @http-managed/interceptors)
        ":http interceptor atom is empty")
    (is (some? (registrar/lookup :event :test.lfvi/ev))
        ":event kind is untouched by the bulk-clear")
    (is (some? (registrar/lookup :sub :test.lfvi/sub))
        ":sub kind is untouched")
    (is (some? (registrar/lookup :fx :test.lfvi/fx))
        ":fx kind is untouched")))

(deftest clear-all-http-interceptors-is-idempotent
  (testing "calling clear-all-http-interceptors! on an empty registry is a no-op"
    (is (= {} @http-managed/interceptors)
        "starting clean (per fixture)")
    (is (nil? (http-managed/clear-all-http-interceptors!))
        "first call returns nil")
    (is (nil? (http-managed/clear-all-http-interceptors!))
        "second call on the already-empty registry is also nil")
    (is (= {} @http-managed/interceptors)
        "atom stays empty")))

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
            interceptor to @http-managed/interceptors on :rf/default."
    (rf/reg-event-fx :oyd1b/register
      (fn [_ _]
        {:fx [[:rf.fx/reg-http-interceptor
               {:id     :oyd1b/auth
                :before identity
                :doc    "fixture interceptor"
                :tags   #{:auth}}]]}))

    (is (empty? (get @http-managed/interceptors :rf/default))
        "pre-dispatch: no interceptors on :rf/default")

    (rf/dispatch-sync [:oyd1b/register])

    (let [chain (get @http-managed/interceptors :rf/default)
          slot  (first (filter #(= :oyd1b/auth (:id %)) chain))]
      (is (= 1 (count chain))
          "the fx wrapper actually mutates the atom (not just a return-value smoke)")
      (is (= :oyd1b/auth (:id slot)))
      (is (fn? (:before slot)))
      (is (= "fixture interceptor" (:doc slot)))
      (is (= #{:auth} (:tags slot))))))

(deftest reg-http-interceptor-fx-honours-explicit-frame-rf2-oyd1b
  (testing "rf2-oyd1b — explicit :frame routes to the named slot, not :rf/default"
    (rf/reg-event-fx :oyd1b/register-on-named
      (fn [_ _]
        {:fx [[:rf.fx/reg-http-interceptor
               {:frame  :rf/api
                :id     :oyd1b/named
                :before identity}]]}))

    (rf/dispatch-sync [:oyd1b/register-on-named])

    (is (empty? (get @http-managed/interceptors :rf/default))
        ":rf/default is not touched by an :rf/api registration")
    (let [chain (get @http-managed/interceptors :rf/api)]
      (is (= 1 (count chain)))
      (is (= :oyd1b/named (:id (first chain)))))))

(deftest clear-http-interceptor-fx-mutates-the-atom-rf2-oyd1b
  (testing "rf2-oyd1b — [:rf.fx/clear-http-interceptor {:id ...}] removes
            the slot from :rf/default (the implicit frame)."
    (http-managed/reg-http-interceptor :oyd1b/to-clear identity)
    (is (= 1 (count (get @http-managed/interceptors :rf/default)))
        "pre-clear: the slot is present")

    (rf/reg-event-fx :oyd1b/clear
      (fn [_ _]
        {:fx [[:rf.fx/clear-http-interceptor {:id :oyd1b/to-clear}]]}))
    (rf/dispatch-sync [:oyd1b/clear])

    (is (empty? (get @http-managed/interceptors :rf/default))
        "the slot is gone after the fx")))

(deftest clear-http-interceptor-fx-honours-explicit-frame-rf2-oyd1b
  (testing "rf2-oyd1b — explicit :frame on the clear fx scopes the removal"
    (http-managed/reg-http-interceptor :oyd1b/scoped {:frame :rf/api} identity)
    (http-managed/reg-http-interceptor :oyd1b/default-survivor identity)

    (rf/reg-event-fx :oyd1b/clear-on-named
      (fn [_ _]
        {:fx [[:rf.fx/clear-http-interceptor
               {:frame :rf/api :id :oyd1b/scoped}]]}))
    (rf/dispatch-sync [:oyd1b/clear-on-named])

    (is (empty? (get @http-managed/interceptors :rf/api))
        ":rf/api lost its slot")
    (is (= 1 (count (get @http-managed/interceptors :rf/default)))
        ":rf/default is unaffected — the clear was scoped to :rf/api")))

(deftest clear-http-interceptor-fx-defaults-frame-to-rf-default-rf2-oyd1b
  (testing "rf2-oyd1b — when :frame is nil/absent the fx routes to :rf/default
            (matching the fn-form behaviour)."
    (http-managed/reg-http-interceptor :oyd1b/dflt identity)
    (rf/reg-event-fx :oyd1b/clear-no-frame
      (fn [_ _]
        ;; No :frame key — must default to :rf/default.
        {:fx [[:rf.fx/clear-http-interceptor {:id :oyd1b/dflt}]]}))
    (rf/dispatch-sync [:oyd1b/clear-no-frame])
    (is (empty? (get @http-managed/interceptors :rf/default)))))

(deftest reg-http-interceptor-fx-rejects-invalid-args-rf2-oyd1b
  (testing "rf2-oyd1b — invalid args (missing :id, missing :before, non-keyword
            id) trigger the same :rf.error/http-bad-interceptor throw the
            fn-form raises. The error fires inside the runtime's fx
            dispatch loop, so we trap via a trace-error listener and
            assert NO interceptor was registered."
    (let [errors (atom [])
          cb-id  ::oyd1b-bad-args]
      (try
        (trace/register-listener!
          cb-id
          (fn [ev]
            (when (= :error (:op-type ev))
              (swap! errors conj ev))))

        ;; missing :id
        (rf/reg-event-fx :oyd1b/bad-no-id
          (fn [_ _]
            {:fx [[:rf.fx/reg-http-interceptor {:before identity}]]}))
        (try (rf/dispatch-sync [:oyd1b/bad-no-id])
             (catch Throwable _ nil))
        (is (empty? (get @http-managed/interceptors :rf/default))
            "missing :id → no interceptor registered")

        ;; missing :before
        (rf/reg-event-fx :oyd1b/bad-no-before
          (fn [_ _]
            {:fx [[:rf.fx/reg-http-interceptor {:id :x}]]}))
        (try (rf/dispatch-sync [:oyd1b/bad-no-before])
             (catch Throwable _ nil))
        (is (empty? (get @http-managed/interceptors :rf/default))
            "missing :before → no interceptor registered")

        ;; non-keyword :id
        (rf/reg-event-fx :oyd1b/bad-string-id
          (fn [_ _]
            {:fx [[:rf.fx/reg-http-interceptor
                   {:id "not-a-keyword" :before identity}]]}))
        (try (rf/dispatch-sync [:oyd1b/bad-string-id])
             (catch Throwable _ nil))
        (is (empty? (get @http-managed/interceptors :rf/default))
            "non-keyword :id → no interceptor registered")

        (finally
          (trace/unregister-listener! cb-id))))))

(deftest reg-and-clear-http-interceptor-fxs-roundtrip-rf2-oyd1b
  (testing "rf2-oyd1b — register-then-clear via fxs round-trips cleanly,
            mirroring the fn-form's idempotency."
    (rf/reg-event-fx :oyd1b/round-trip
      (fn [_ _]
        {:fx [[:rf.fx/reg-http-interceptor
               {:id :oyd1b/rt :before identity}]
              [:rf.fx/clear-http-interceptor
               {:id :oyd1b/rt}]]}))
    (rf/dispatch-sync [:oyd1b/round-trip])
    (is (empty? (get @http-managed/interceptors :rf/default))
        "register followed by clear in the same event leaves the chain empty")))
