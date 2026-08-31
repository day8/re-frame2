(ns re-frame.http-abort-body-prep-race-test
  "rf2-rsv2n (JVM) — an abort that wins while `run-attempt!` is blocked in
  the managed request-preparation phase must never subsequently enter the
  host transport.

  Defect (closed by this test): `run-attempt!` sampled `@finalised?` only
  BEFORE `prepare-body!`, and a `:body` thunk may block inside that call
  for arbitrarily long. An abort landing in that window delivered the
  canonical cancelled reply and cleared the in-flight registry — the
  framework told the app the request was cancelled — yet the same attempt
  then proceeded into the successful-preparation branch and called
  `jvm-fetch`, so `HttpClient.sendAsync` issued a side-effecting request
  AFTER cancellation. During preparation `cf-holder` is still nil, so the
  abort closure had no future to cancel either: the request escaped
  cancellation entirely (Spec 014 §Abort precedence — body realization is
  a managed phase; §Aborts — a cancelled request must not issue a fresh
  attempt).

  Fix: a one-cell issuance-phase CAS (`issue-phase`, nil → `:issued` |
  `:aborted`) shared between the abort closure and the post-preparation
  gate, so the abort/issuance race has exactly one winner. Abort-before-
  send wins the cell and the transport is never entered; send-before-abort
  loses the cell to issuance, which publishes the cancellable future and
  re-reads the abort-precedence cell after publication, cancelling its own
  future when the abort landed in the residual publish window. The gate is
  shared CLJC with no reader conditional, so the same guard covers a
  re-entrantly fired abort during CLJS body realization (the third test
  here drives that exact single-threaded re-entrant ordering through the
  shared code path on the JVM).

  All tests stub `transport-jvm/jvm-fetch` (the host-transport seam the
  bug escapes through — the `with-managed-request-stubs` layer overrides
  the whole `:rf.http/managed` fx and so sits ABOVE the lifecycle under
  test) and use latches/promises, never timing sleeps."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as registry]
            [re-frame.http.transport :as transport]
            [re-frame.http.transport-jvm :as transport-jvm]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-abort-body-prep-race condition"})
   true))

(defn- register-recorder-and-issue!
  "Register the shared `:reply/recorder` event plus an `:issue` event that
  fires `:rf.http/managed` with `request` under `request-id`, recording
  every reply into the `replies` atom."
  [replies request-id request]
  (rf/reg-event :reply/recorder
    (fn [_ [_ payload]] (swap! replies conj payload) {}))
  (rf/reg-event :issue
    (fn [_ _]
      {:fx [[:rf.http/managed
             {:request    request
              :decode     :text
              :request-id request-id
              :on-failure [:reply/recorder]
              :on-success [:reply/recorder]}]]})))

;; ---- (1) abort while the body thunk holds preparation ----------------------

(deftest abort-during-body-prep-never-enters-host-transport
  (testing "rf2-rsv2n — an abort that completes while the body thunk holds request preparation yields ZERO jvm-fetch calls, exactly one canonical :status :cancelled reply, and an empty registry"
    (let [fetch-calls   (atom 0)
          thunk-entered (promise)
          release       (promise)
          replies       (atom [])]
      (with-redefs [transport-jvm/jvm-fetch (fn [_]
                                              (swap! fetch-calls inc)
                                              (CompletableFuture.))]
        (register-recorder-and-issue!
          replies :prep-race
          {:url    "http://127.0.0.1:0/x"
           :method :post
           ;; The thunk signals entry, then blocks until the test releases
           ;; it — holding the attempt inside `prepare-body!` while the
           ;; abort races it (latches, not sleeps).
           :body   (fn []
                     (deliver thunk-entered true)
                     @release
                     "held-body")})
        (let [worker (future (rf/dispatch-sync [:issue]))]
          (try
            (is (true? (deref thunk-entered 5000 false))
                "the body thunk entered — preparation is in progress")
            (is (contains? (registry/in-flight-snapshot) :prep-race)
                "the handle was registered before preparation began")
            ;; The abort resolves the live handle and COMPLETES (registry
            ;; cleared synchronously by the abort closure) while the thunk
            ;; is still held.
            (is (true? (registry/abort-in-flight! :prep-race :user))
                "the abort resolved the in-flight handle before release")
            (is (empty? (registry/in-flight-snapshot))
                "the registry is cleared before the body thunk is released")
            (is (zero? @fetch-calls)
                "no transport call while preparation is held")
            ;; Release the thunk: preparation returns successfully, and the
            ;; post-preparation gate must now SKIP the host transport.
            (deliver release true)
            (is (not= ::timeout (deref worker 5000 ::timeout))
                "the attempt returned after release")
            (is (zero? @fetch-calls)
                "jvm-fetch was NEVER called after the cancelled reply — the cancelled attempt must not reach sendAsync")
            (await-condition! #(seq @replies))
            (is (= 1 (count @replies))
                "exactly one terminal reply — the cancellation")
            (let [reply (first @replies)]
              (is (= :cancelled (:status reply))
                  "the single reply is the canonical cancellation")
              (is (= :rf.http/aborted (get-in reply [:error :kind])))
              (is (= :user (get-in reply [:error :reason]))))
            (is (empty? (registry/in-flight-snapshot))
                "the request-id registry stays clean")
            (is (empty? (registry/actor-in-flight-snapshot))
                "the actor registry stays clean")
            (finally
              (deliver release true)
              (deref worker 5000 nil))))))))

;; ---- (2) non-vacuity control: same harness, no abort -----------------------

(deftest no-abort-control-enters-host-transport-exactly-once
  (testing "rf2-rsv2n (non-vacuity control) — the identical harness WITHOUT an abort invokes the host transport exactly once and completes normally"
    (let [fetch-calls   (atom 0)
          thunk-entered (promise)
          release       (promise)
          replies       (atom [])]
      (with-redefs [transport-jvm/jvm-fetch
                    (fn [_]
                      (swap! fetch-calls inc)
                      (CompletableFuture/completedFuture
                        {:ok?         true
                         :status      200
                         :status-text ""
                         :headers     {}
                         :body-text   "ok"}))]
        (register-recorder-and-issue!
          replies :prep-control
          {:url    "http://127.0.0.1:0/x"
           :method :post
           :body   (fn []
                     (deliver thunk-entered true)
                     @release
                     "held-body")})
        (let [worker (future (rf/dispatch-sync [:issue]))]
          (try
            (is (true? (deref thunk-entered 5000 false))
                "the body thunk entered — the harness holds preparation exactly as the abort case does")
            ;; No abort: release immediately.
            (deliver release true)
            (is (not= ::timeout (deref worker 5000 ::timeout)))
            (is (= 1 @fetch-calls)
                "the host transport was entered exactly once — the gate did not disable the send branch")
            (await-condition! #(seq @replies))
            (is (= 1 (count @replies)))
            (is (= :ok (:status (first @replies)))
                "the request completed normally through the stubbed transport")
            (is (empty? (registry/in-flight-snapshot)))
            (finally
              (deliver release true)
              (deref worker 5000 nil))))))))

;; ---- (3) re-entrant abort fired BY body realization ------------------------

(deftest reentrant-abort-inside-body-thunk-never-enters-host-transport
  (testing "rf2-rsv2n — an abort fired synchronously/re-entrantly FROM INSIDE the body thunk (the single-threaded ordering the CLJS host produces) is honoured by the shared post-prep gate: no transport call follows the cancellation"
    (let [fetch-calls  (atom 0)
          abort-result (atom ::not-fired)
          replies      (atom [])]
      (with-redefs [transport-jvm/jvm-fetch (fn [_]
                                              (swap! fetch-calls inc)
                                              (CompletableFuture.))]
        (register-recorder-and-issue!
          replies :prep-reentrant
          {:url    "http://127.0.0.1:0/x"
           :method :post
           ;; The thunk aborts its OWN request mid-realization, then
           ;; returns successfully — preparation succeeds, and the gate
           ;; alone stands between the delivered cancellation and the send.
           :body   (fn []
                     (reset! abort-result
                             (registry/abort-in-flight! :prep-reentrant :user))
                     "reentrant-body")})
        (rf/dispatch-sync [:issue])
        (is (true? @abort-result)
            "the re-entrant abort resolved the live handle during realization")
        (is (zero? @fetch-calls)
            "the host transport was never entered after the re-entrant cancellation")
        (await-condition! #(seq @replies))
        (is (= 1 (count @replies)) "exactly one terminal reply")
        (let [reply (first @replies)]
          (is (= :cancelled (:status reply)))
          (is (= :rf.http/aborted (get-in reply [:error :kind]))))
        (is (empty? (registry/in-flight-snapshot)))))))

;; ---- (4) handoff controls: issuance wins the phase CAS ---------------------

(deftest abort-after-issuance-cancels-published-future
  (testing "rf2-rsv2n (handoff control) — when issuance wins, the future is published and cancellable: a subsequent abort cancels it via cf-holder and produces no double reply"
    (let [fetch-calls (atom 0)
          returned-cf (atom nil)
          replies     (atom [])]
      (with-redefs [transport-jvm/jvm-fetch (fn [_]
                                              (swap! fetch-calls inc)
                                              (let [cf (CompletableFuture.)]
                                                (reset! returned-cf cf)
                                                cf))]
        (register-recorder-and-issue!
          replies :issued-then-abort
          {:url "http://127.0.0.1:0/x" :method :post :body "plain"})
        ;; Issuance completes synchronously inside dispatch-sync: the stub
        ;; was entered, the future published, whenComplete wired.
        (rf/dispatch-sync [:issue])
        (is (= 1 @fetch-calls) "issuance won — the transport was entered once")
        (is (some? @returned-cf) "the transport future exists")
        (is (true? (registry/abort-in-flight! :issued-then-abort :user))
            "the abort resolved the in-flight handle")
        (is (.isCancelled ^CompletableFuture @returned-cf)
            "the abort cancelled the PUBLISHED future — the work actually stops")
        (await-condition! #(seq @replies))
        (is (= 1 (count @replies))
            "exactly one terminal reply — the CancellationException completion did not double-reply")
        (let [reply (first @replies)]
          (is (= :cancelled (:status reply)))
          (is (= :rf.http/aborted (get-in reply [:error :kind]))))
        (is (empty? (registry/in-flight-snapshot)))))))

(deftest abort-in-residual-publish-window-still-cancels-future
  (testing "rf2-rsv2n (residual window) — an abort landing AFTER issuance won the phase CAS but BEFORE the future is published (cf-holder still nil, injected deterministically at :issue/before-send) is honoured by the issuing thread's post-publication re-check: the future is cancelled on the abort's behalf, one reply, clean registry"
    (let [fetch-calls  (atom 0)
          returned-cf  (atom nil)
          abort-result (atom ::not-fired)
          replies      (atom [])]
      (try
        (with-redefs [transport-jvm/jvm-fetch (fn [_]
                                                (swap! fetch-calls inc)
                                                (let [cf (CompletableFuture.)]
                                                  (reset! returned-cf cf)
                                                  cf))]
          (register-recorder-and-issue!
            replies :residual-window
            {:url "http://127.0.0.1:0/x" :method :post :body "plain"})
          ;; The seam: fire the abort at the point where issuance has
          ;; committed (phase CAS won) but nothing is published yet — the
          ;; exact window the abort closure's cf-holder read finds nil.
          (transport/set-test-interleave-hook!
            (fn [point ctx]
              (when (and (= point :issue/before-send)
                         (= :residual-window (:request-id ctx))
                         (= ::not-fired @abort-result))
                (reset! abort-result
                        (registry/abort-in-flight! :residual-window :user)))))
          (rf/dispatch-sync [:issue])
          (is (true? @abort-result)
              "the injected abort resolved the handle inside the residual window")
          (is (= 1 @fetch-calls)
              "issuance had already committed, so the transport WAS entered in this ordering")
          (is (some? @returned-cf))
          (is (.isCancelled ^CompletableFuture @returned-cf)
              "the issuing thread's post-publication re-check cancelled the future on the abort's behalf")
          (await-condition! #(seq @replies))
          (is (= 1 (count @replies)) "exactly one terminal reply — the abort's")
          (let [reply (first @replies)]
            (is (= :cancelled (:status reply)))
            (is (= :rf.http/aborted (get-in reply [:error :kind]))))
          (is (empty? (registry/in-flight-snapshot)))
          (is (empty? (registry/actor-in-flight-snapshot))))
        (finally
          (transport/set-test-interleave-hook! nil))))))
