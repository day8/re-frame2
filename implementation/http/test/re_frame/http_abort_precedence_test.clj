(ns re-frame.http-abort-precedence-test
  "Per rf2-wez75 (Mike decision a, 2026-05-17): abort always wins over
  decode-failure / transport / accept-failure / success classification
  in `:rf.http/managed`. Aligned with Fetch AbortController / Node HTTP
  / JVM HttpClient / gRPC universal convention.

  Spec references:
   - Spec 014 §Abort precedence (abort always wins)
   - Spec 014 §Aborts (`:request-id` (internal), `:abort-signal` (external))
   - Spec 014 §Abort on actor destroy (rf2-wvkn)

  Test strategy: each test deterministically interleaves the abort with a
  late-classifying failure / success by gating the response side on a
  `CountDownLatch`. Either the server blocks (decode never gets to run
  while the abort is fired against an in-flight request) or a custom
  decoder blocks (decode is mid-run when the abort fires — the most
  contended race). The abort-wins seam in `re-frame.http-transport`
  (`finalise-failure!` / `finalise-success!` re-sample the handle's
  `:aborted?` cell after winning the once-only CAS) collapses the
  observable outcome to `:rf.http/aborted` regardless of which side
  classified first.

  Three scenarios (each its own deftest):
   1. abort-during-in-flight-decode-wins-over-decode-failure
   2. abort-during-transport-error-wins-over-transport-classification
   3. abort-via-actor-destroy-wins-over-decode-failure"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http-managed :as http-managed]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress ServerSocket]
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
  (machines/reset-timers!)
  (http-managed/clear-all-in-flight!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- start-200-server!
  "Start an HttpServer that always returns 200 with the given body. The
  server processes requests on its default executor; no blocking on the
  server side — the test interleaves on the decoder side via `latches`."
  [content-type body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              bs (.getBytes (str body) "UTF-8")]
                          (when content-type
                            (-> ex .getResponseHeaders (.set "Content-Type" content-type)))
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

(defn- pick-closed-port!
  "Bind a transient socket on 127.0.0.1, capture the port, close. The
  port is briefly free and unlikely to be re-bound before the test's
  HTTP attempt — chosen specifically to provoke a connection-refused /
  transport-error in the JVM transport classifier."
  []
  (let [s (ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        p (.getLocalPort s)]
    (.close s)
    p))

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

;; ---- (1) abort during in-flight decode — must beat :rf.http/decode-failure
;;
;; Setup: server returns 200 with a JSON-shaped body; the request supplies
;; a custom :decode fn that blocks on `decoder-entered` (signalling the
;; test thread that decode is mid-run) and then blocks on `decoder-may-
;; proceed` until the test fires the abort and lets the decoder throw
;; (synthesising a decode-failure classification). Without rf2-wez75's
;; abort-precedence seam, whichever of (a) the abort-fn's CAS or (b) the
;; thrown-decoder's finalise-failure! CAS arrived first would win, and a
;; user would observe :rf.http/decode-failure on the reply despite
;; explicitly aborting. With the seam in place, the handle's :aborted?
;; cell is sampled inside finalise-failure! AFTER winning the CAS, so
;; the abort observation always wins regardless of CAS ordering.

(deftest abort-during-in-flight-decode-wins-over-decode-failure
  (testing "rf2-wez75 — abort fired while a slow decoder is in-flight reclassifies the reply as :rf.http/aborted, not :rf.http/decode-failure"
    (let [srv               (start-200-server! "application/json" "{\"k\":1}")
          decoder-entered   (CountDownLatch. 1)
          decoder-may-throw (CountDownLatch. 1)
          replies           (atom [])]
      (try
        (rf/reg-event-fx :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event-fx :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     (fn [_text _headers]
                                  (.countDown decoder-entered)
                                  (.await decoder-may-throw 30 TimeUnit/SECONDS)
                                  ;; Synthesise decode-failure — the
                                  ;; finalise-failure! call site catches
                                  ;; this and classifies as
                                  ;; :rf.http/decode-failure absent the
                                  ;; rf2-wez75 abort-precedence seam.
                                  (throw (ex-info "decode-boom" {})))
                    :request-id :race
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/reg-event-fx :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
        (rf/dispatch-sync [:issue])
        ;; Wait for the decoder to enter — guarantees the response has
        ;; landed and decode is mid-run. The in-flight registry holds
        ;; the request handle right now.
        (is (.await decoder-entered 5 TimeUnit/SECONDS)
            "decoder entered — response landed, decode in progress, abort window open")
        (is (= 1 (count (http-managed/in-flight-snapshot))))
        ;; Fire the abort BEFORE letting the decoder throw. Both the
        ;; abort-fn AND the about-to-fire finalise-failure! race for the
        ;; once-only :finalised? CAS — but the abort-fn flips :aborted?
        ;; FIRST, and finalise-failure! samples that cell after winning
        ;; the CAS, so the visible classification is :rf.http/aborted.
        (rf/dispatch-sync [:do/abort])
        (.countDown decoder-may-throw)
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :failure (:kind reply))
              "the abort-precedence seam dispatches a failure reply, not a success")
          (is (= :rf.http/aborted (get-in reply [:failure :kind]))
              "the reply MUST be :rf.http/aborted, NOT :rf.http/decode-failure (rf2-wez75 Mike decision a)")
          (is (= :user (get-in reply [:failure :reason]))
              "user-initiated abort surfaces :reason :user"))
        (is (= 1 (count @replies))
            "exactly one reply — the once-only CAS still pins single-dispatch")
        (is (empty? (http-managed/in-flight-snapshot))
            "in-flight registry is clean after the aborted reply")
        (finally
          (stop-server! srv))))))

;; ---- (2) abort during transport-error — must beat :rf.http/transport ------
;;
;; Setup: target a port that nothing's listening on so the JVM
;; HttpClient surfaces a connection-refused / transport error from
;; sendAsync. We fire the abort fx in the SAME dispatch as the managed
;; request — the supersede semantics don't apply (different request-id),
;; so the test exercises the user-abort-during-transport-error race.
;;
;; In practice the abort-fn flips :aborted? before the transport's
;; whenComplete callback synthesises the :rf.http/transport failure;
;; the precedence seam in finalise-failure! observes the flip and
;; reclassifies. Because the JVM CompletableFuture path completes-
;; exceptionally with a CancellationException once .cancel cf true fires,
;; the natural classifier may produce :rf.http/aborted directly — but
;; in either case the reply MUST be :rf.http/aborted, never
;; :rf.http/transport. This test pins the contract end-to-end.

(deftest abort-during-transport-error-wins-over-transport-classification
  (testing "rf2-wez75 — abort fired against an in-flight request to an unreachable host yields :rf.http/aborted, not :rf.http/transport"
    (let [closed-port (pick-closed-port!)
          replies     (atom [])]
      (rf/reg-event-fx :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      ;; Issue the request and then immediately fire the abort against
      ;; the same request-id. The transport hasn't finished resolving
      ;; the connection-refused error yet (sendAsync is async).
      (rf/reg-event-fx :issue
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url (str "http://127.0.0.1:" closed-port "/")}
                  :request-id :race
                  ;; Long-but-finite timeout — we want the test to bound,
                  ;; not the transport.
                  :decode     :json
                  :on-failure [:reply/recorder]
                  :on-success [:reply/recorder]}]
                [:rf.http/managed-abort :race]]}))
      (rf/dispatch-sync [:issue])
      ;; Wait for SOME reply (either the abort beat the transport or
      ;; vice versa). The contract under rf2-wez75 is that whichever
      ;; arrived first, the reply MUST be :rf.http/aborted.
      (await-condition! #(seq @replies))
      (let [reply (first @replies)]
        (is (= :failure (:kind reply))
            "transport-error-or-aborted scenario surfaces a failure reply")
        (is (= :rf.http/aborted (get-in reply [:failure :kind]))
            "abort precedence pins the reply to :rf.http/aborted regardless of transport-classifier race ordering (rf2-wez75 Mike decision a)")
        (is (contains? #{:user :request-id-superseded}
                       (get-in reply [:failure :reason]))
            ":reason discriminates user-abort from supersede; both are abort-flavoured"))
      (is (= 1 (count @replies))
          "exactly one reply — abort precedence does not double-dispatch")
      (is (empty? (http-managed/in-flight-snapshot))
          "in-flight registry is clean"))))

;; ---- (3) abort-via-actor-destroy wins over decode-failure -----------------
;;
;; Setup: same slow-decoder gimmick as test (1), but the abort source
;; is `abort-on-actor-destroy` (rf2-wvkn) — the cascade that fires when
;; a spawned state-machine actor is destroyed. The request rides under
;; the actor-in-flight index; destroying the actor calls the abort-fn
;; with `:reason :actor-destroyed`. The precedence seam still observes
;; the :aborted? flip, so the visible reply is :rf.http/aborted with
;; :reason :actor-destroyed (the trace event :rf.http/aborted-on-actor-
;; destroy fires independently from the abort-on-actor-destroy walker).

(deftest abort-via-actor-destroy-wins-over-decode-failure
  (testing "rf2-wez75 — actor-destroy abort wins over a synchronously-firing decode-failure; reply is :rf.http/aborted with :reason :actor-destroyed"
    (let [srv               (start-200-server! "application/json" "{\"k\":1}")
          decoder-entered   (CountDownLatch. 1)
          decoder-may-throw (CountDownLatch. 1)
          replies           (atom [])]
      (try
        (rf/reg-event-fx :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; Child machine — :entry fires the managed request with a
        ;; latch-gated decoder. The decoder synthesises a decode-
        ;; failure when released, but the abort-precedence seam
        ;; intercepts.
        (rf/reg-machine :worker/race
          {:initial :idle
           :data    {:port (:port srv)}
           :actions {:fire (fn [data _]
                             {:fx [[:rf.http/managed
                                    {:request    {:url (str "http://127.0.0.1:" (:port data) "/")}
                                     :decode     (fn [_text _headers]
                                                   (.countDown decoder-entered)
                                                   (.await decoder-may-throw 30 TimeUnit/SECONDS)
                                                   (throw (ex-info "decode-boom" {})))
                                     :request-id :race
                                     :on-failure [:reply/recorder]
                                     :on-success [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire}}})
        ;; Parent: :spawn spawns the child, :cancel transitions out.
        (rf/reg-machine :sup/race
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:spawn {:machine-id :worker/race
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/race [:start]])
        ;; Wait for the decoder to enter — response landed, decode in
        ;; progress, child is in-flight under actor-in-flight.
        (is (.await decoder-entered 5 TimeUnit/SECONDS)
            "decoder entered while request was in flight under spawned actor")
        (is (= 1 (count (http-managed/actor-in-flight-snapshot))))
        ;; Destroy the actor — abort-on-actor-destroy walks the
        ;; actor-in-flight index and fires :abort-fn with
        ;; :reason :actor-destroyed.
        (rf/dispatch-sync [:sup/race [:cancel]])
        ;; Release the decoder — it throws, but the precedence seam
        ;; has already flipped :aborted? so finalise-failure! observes
        ;; the abort and reclassifies.
        (.countDown decoder-may-throw)
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :failure (:kind reply))
              "actor-destroy abort surfaces as a failure reply")
          (is (= :rf.http/aborted (get-in reply [:failure :kind]))
              "abort precedence wins — reply MUST be :rf.http/aborted, NOT :rf.http/decode-failure (rf2-wez75 Mike decision a)")
          (is (= :actor-destroyed (get-in reply [:failure :reason]))
              ":reason discriminates the actor-destroy source from a user abort"))
        (is (= 1 (count @replies)))
        (is (empty? (http-managed/actor-in-flight-snapshot))
            "actor-in-flight index is clean after the actor-destroy cascade")
        (finally
          (stop-server! srv))))))
