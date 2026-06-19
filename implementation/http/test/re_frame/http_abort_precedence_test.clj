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
  late-classifying failure / success by gating the contended side rather
  than racing wall-clock timing. The server blocks on a `CountDownLatch`
  (the response, hence decode, never gets to run while the abort is fired
  against an in-flight request), or a custom decoder blocks (decode is
  mid-run when the abort fires — the most contended race), or the
  finalise seam is driven DIRECTLY with the abort intent already recorded
  on the handle (the transport-classification precedence, pinned without
  any cross-thread timing). The abort-wins seam in
  `re-frame.http.transport` (`finalise-failure!` / `finalise-success!`
  re-sample the handle's `:aborted?` cell after winning the once-only CAS)
  collapses the observable outcome to `:rf.http/aborted` regardless of
  which side classified first.

  Four scenarios (each its own deftest):
   1. abort-during-in-flight-decode-wins-over-decode-failure
   2. abort-during-in-flight-transport-wins-over-transport-classification
      (deterministic end-to-end: an accept-but-never-respond server holds
      the request in-flight so the user abort lands before any transport
      classification can — the realistic transport-vs-abort case)
   2b. transport-classification-loses-to-recorded-abort-precedence-seam
       (deterministic unit-level: drives `finalise-failure!` with a
       `:rf.http/transport` failure on a handle whose `:aborted?` is
       ALREADY flipped — pins the exact reclassification the flaky
       same-dispatch-vs-async race (rf2-12r1dn) was probing by timing)
   3. abort-via-actor-destroy-wins-over-decode-failure"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as http-registry]
            [re-frame.http.transport :as http-transport]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom])
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
  (machines/reset-timers!)
  (http-managed/clear-all-in-flight!)
  (rf/with-frame :rf/default
    (t)))

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

(defn- start-stalled-server!
  "Start an HttpServer that ACCEPTS the connection but blocks the handler
  on `latch`, so the request stays genuinely in-flight (connected, awaiting
  a response) until the test releases the latch at teardown. The JVM
  HttpClient's `sendAsync` future therefore CANNOT resolve a transport
  error on its own — the only way the in-flight request completes is the
  abort the test fires, so the abort-vs-transport-classification ordering is
  deterministic by construction rather than a wall-clock race against a
  connection-refused resolution (rf2-12r1dn)."
  [^CountDownLatch latch]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex]
                          ;; Hold the exchange open until the test releases
                          ;; the latch at teardown; the request is in-flight
                          ;; (connected, no response) for the whole window
                          ;; the abort is fired in.
                          (.await latch 30 TimeUnit/SECONDS)
                          (try
                            (.sendResponseHeaders ex 204 -1)
                            (catch Throwable _ nil))
                          nil))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))}))

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
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
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
        (rf/reg-event :do/abort
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

;; ---- (2) abort during transport — must beat :rf.http/transport -----------
;;
;; rf2-12r1dn — DETERMINISM FIX. The prior shape targeted a CLOSED PORT and
;; fired the abort fx in the SAME synchronous dispatch as the managed
;; request, then asserted abort wins. That is a wall-clock RACE, not a
;; sequenced ordering: `:rf.http/managed` calls `sendAsync`, which resolves
;; the connection-refused transport error on the JVM HttpClient's OWN
;; executor thread, concurrently with the (synchronous) abort fx that runs
;; next in the fx-vector. On a fast runner the transport completion reaches
;; `finalise-failure!` and wins the once-only `:finalised?` CAS BEFORE the
;; abort-fn flips `:aborted?` — so `finalise-failure!`'s abort-precedence
;; re-sample reads nil and the `:rf.http/transport` reply lands. The
;; abort-wins seam is correct; it can only reclassify a failure when the
;; abort intent is RECORDED before finalisation, and the same-dispatch
;; shape did not guarantee that ordering (it passed on Windows / origin/main
;; CI, failed intermittently on Linux CI — environment-dependent timing).
;;
;; This is fixed deterministically WITHOUT a sleep, in two complementary
;; tests:
;;
;;   (2)  end-to-end — an ACCEPT-but-never-respond server holds the request
;;        genuinely in-flight (connected, awaiting a response). The transport
;;        future therefore cannot resolve a transport error on its own; the
;;        test polls `in-flight-snapshot` until the handle is registered (the
;;        deterministic gate that the request is in-flight) THEN fires the
;;        abort. `.cancel cf true` completes the future exceptionally with a
;;        CancellationException → `classify-jvm-error` → `:rf.http/aborted`.
;;        Abort wins by construction, not by timing.
;;
;;   (2b) unit-level — drives `finalise-failure!` DIRECTLY with a
;;        `:rf.http/transport` failure on a handle whose `:aborted?` cell is
;;        ALREADY flipped, pinning the exact reclassification the flaky race
;;        was probing: transport classified first, abort intent recorded, →
;;        the visible reply MUST be `:rf.http/aborted`. Zero cross-thread
;;        timing; the precedence is asserted, not sampled.

(deftest abort-during-in-flight-transport-wins-over-transport-classification
  (testing "rf2-12r1dn / rf2-wez75 — a user abort fired against a genuinely in-flight request (held open by an accept-but-never-respond server) yields :rf.http/aborted, deterministically, never :rf.http/transport"
    (let [release (CountDownLatch. 1)
          {:keys [port] :as srv} (start-stalled-server! release)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" port "/")}
                    :request-id :race
                    :decode     :json
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/reg-event :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
        (rf/dispatch-sync [:issue])
        ;; Deterministic gate: the request is REGISTERED and in-flight (the
        ;; connection is established, the server is blocked, no transport
        ;; error is possible) before we fire the abort. This replaces the
        ;; flaky reliance on the abort fx out-racing an async connection-
        ;; refused resolution.
        (await-condition! #(seq (http-managed/in-flight-snapshot)))
        (is (= 1 (count (http-managed/in-flight-snapshot)))
            "request is in-flight against the stalled server — abort window open, no transport error possible")
        (rf/dispatch-sync [:do/abort])
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :failure (:kind reply))
              "the abort surfaces as a :failure reply, not a success")
          (is (= :rf.http/aborted (get-in reply [:failure :kind]))
              "the reply MUST be :rf.http/aborted, NOT :rf.http/transport (rf2-wez75 Mike decision a)")
          (is (= :user (get-in reply [:failure :reason]))
              "user-initiated abort surfaces :reason :user"))
        (is (= 1 (count @replies))
            "exactly one reply — abort precedence does not double-dispatch")
        (is (empty? (http-managed/in-flight-snapshot))
            "in-flight registry is clean after the aborted reply")
        (finally
          (.countDown release)
          (stop-server! srv))))))

;; The finalise-failure! private is reached the same way (6c) reaches
;; schedule-backoff-handle! — a #'-deref of the private var.
(def ^:private finalise-failure!*
  @#'http-transport/finalise-failure!)

(deftest transport-classification-loses-to-recorded-abort-precedence-seam
  (testing "rf2-12r1dn — finalise-failure! driven with a :rf.http/transport failure on a handle whose :aborted? is ALREADY flipped reclassifies the reply to :rf.http/aborted; this pins the exact precedence the flaky same-dispatch-vs-async-transport race was probing, with no cross-thread timing"
    (let [replies (atom [])]
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      ;; A handle stamped exactly as run-attempt!'s record-in-flight! would:
      ;; a once-only :finalised? CAS cell and the :aborted? precedence cell —
      ;; here PRE-FLIPPED to model the abort-fn having recorded the abort
      ;; intent BEFORE the transport's whenComplete reaches finalise-failure!.
      (let [finalised? (atom false)
            aborted?   (atom {:reason :user :actor-id nil})
            handle     (http-registry/record-in-flight!
                         :race nil
                         {:abort-fn   (fn [_] nil)
                          :url        "http://127.0.0.1:1/"
                          :finalised? finalised?
                          :aborted?   aborted?
                          :sensitive? false
                          :frame      :rf/default})
            ctx        {:request-id          :race
                        :url                 "http://127.0.0.1:1/"
                        :handle              handle
                        :frame               :rf/default
                        :sensitive?          false
                        :origin-event        [:issue]
                        :explicit-on-failure {:supplied? true :value [:reply/recorder]}}]
        ;; The transport classifier produced :rf.http/transport (the losing
        ;; classification in the bead's failing CI run). finalise-failure!
        ;; samples the already-flipped :aborted? cell after winning the CAS
        ;; and replaces the failure with the canonical aborted shape.
        (finalise-failure!* ctx {:kind :rf.http/transport :message "Connection refused" :cause "java.net.ConnectException"})
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :failure (:kind reply))
              "the reclassified outcome is a :failure reply")
          (is (= :rf.http/aborted (get-in reply [:failure :kind]))
              "a recorded abort RECLASSIFIES a transport classification to :rf.http/aborted — the abort-wins precedence the race was probing")
          (is (= :user (get-in reply [:failure :reason]))
              "the abort snapshot's :reason rides the reclassified reply"))
        (is (= 1 (count @replies))
            "exactly one reply — the once-only :finalised? CAS still pins single-dispatch")
        (is (empty? (http-managed/in-flight-snapshot))
            "finalise-failure! cleared the in-flight registry")))))

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
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; Child machine — :entry fires the managed request with a
        ;; latch-gated decoder. The decoder synthesises a decode-
        ;; failure when released, but the abort-precedence seam
        ;; intercepts.
        (rf/reg-machine :worker/race
          {:initial :idle
           :data    {:port (:port srv)}
           :actions {:fire (fn [{data :data}]
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
