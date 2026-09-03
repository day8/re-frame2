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
  `:aborted`) shared between the abort closure and the host-entry region,
  so the abort/issuance race has exactly one winner — and the commit is
  the host call itself, not a point ahead of it. Abort-before-entry wins
  the cell and the transport is never entered; entry-before-abort wins it
  and publishes the cancellable future. On the JVM the commit CAS, the
  `sendAsync` call and that publication are one critical region under a
  per-attempt monitor which the abort closure's cancel step also takes, so
  an abort racing an in-flight host call cannot complete ahead of it: it
  waits, then cancels a published future. CLJS needs no monitor — the host
  is single-threaded, so the CAS immediately before `cljs-fetch` orders the
  only interleaving that host can produce (a re-entrantly fired abort
  during body realization; the third test here drives exactly that
  single-threaded ordering through the JVM's copy of the same gate).

  Committing ahead of the host call is what the audit of PR #8842 found
  still open, and the last two tests here are its regression pair: an
  abort that COMPLETES at the issuance boundary must leave zero host
  calls behind it, and an abort that races an in-flight host call must be
  unable to complete until that call has published its future.

  All tests stub `transport-jvm/jvm-fetch` (the host-transport seam the
  bug escapes through — the `with-managed-request-stubs` layer overrides
  the whole `:rf.http/managed` fx and so sits ABOVE the lifecycle under
  test) and use latches / promises / thread-state observation, never
  timing sleeps."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport :as rf.http.transport]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.lang Thread$State]
           [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-abort-body-prep-race condition"})
   true))

(defn- await-blocked!
  "Poll `t` until it reports `BLOCKED` — waiting to enter a monitor — or the
  budget runs out; returns true iff BLOCKED was observed. This is a state
  observation, not a timing assumption, and it is decisive in both
  directions: a thread that must take a monitor another thread holds enters
  BLOCKED and stays there until released, while a thread with no monitor to
  take never enters it at all and the poll runs out its budget."
  [^Thread t timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (cond
        (= Thread$State/BLOCKED (.getState t)) true
        (> (System/nanoTime) deadline)         false
        :else                                  (do (Thread/sleep 5) (recur))))))

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
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_]
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
            (is (contains? (rf.http.registry/in-flight-snapshot) :prep-race)
                "the handle was registered before preparation began")
            ;; The abort resolves the live handle and COMPLETES (registry
            ;; cleared synchronously by the abort closure) while the thunk
            ;; is still held.
            (is (true? (rf.http.registry/abort-in-flight! :prep-race :user))
                "the abort resolved the in-flight handle before release")
            (is (empty? (rf.http.registry/in-flight-snapshot))
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
            (is (empty? (rf.http.registry/in-flight-snapshot))
                "the request-id registry stays clean")
            (is (empty? (rf.http.registry/actor-in-flight-snapshot))
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
      (with-redefs [rf.http.transport-jvm/jvm-fetch
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
            (is (empty? (rf.http.registry/in-flight-snapshot)))
            (finally
              (deliver release true)
              (deref worker 5000 nil))))))))

;; ---- (3) re-entrant abort fired BY body realization ------------------------

(deftest reentrant-abort-inside-body-thunk-never-enters-host-transport
  (testing "rf2-rsv2n — an abort fired synchronously/re-entrantly FROM INSIDE the body thunk (the single-threaded ordering the CLJS host produces) is honoured by the shared post-prep gate: no transport call follows the cancellation"
    (let [fetch-calls  (atom 0)
          abort-result (atom ::not-fired)
          replies      (atom [])]
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_]
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
                             (rf.http.registry/abort-in-flight! :prep-reentrant :user))
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
        (is (empty? (rf.http.registry/in-flight-snapshot)))))))

;; ---- (4) handoff control: issuance wins, the future is cancellable ---------

(deftest abort-after-issuance-cancels-published-future
  (testing "rf2-rsv2n (handoff control) — when issuance wins, the future is published and cancellable: a subsequent abort cancels it via cf-holder and produces no double reply"
    (let [fetch-calls (atom 0)
          returned-cf (atom nil)
          replies     (atom [])]
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_]
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
        (is (true? (rf.http.registry/abort-in-flight! :issued-then-abort :user))
            "the abort resolved the in-flight handle")
        (is (.isCancelled ^CompletableFuture @returned-cf)
            "the abort cancelled the PUBLISHED future — the work actually stops")
        (await-condition! #(seq @replies))
        (is (= 1 (count @replies))
            "exactly one terminal reply — the CancellationException completion did not double-reply")
        (let [reply (first @replies)]
          (is (= :cancelled (:status reply)))
          (is (= :rf.http/aborted (get-in reply [:error :kind]))))
        (is (empty? (rf.http.registry/in-flight-snapshot)))))))

;; ---- (5) the issuance boundary: a completed abort issues nothing -----------

;; This test was `abort-in-residual-publish-window-still-cancels-future`
;; before the audit of PR #8842. It fired the same abort at the same seam and
;; then asserted `(= 1 @fetch-calls)` — blessing the very ordering the bead
;; forbids, where an abort completes (returns true, clears the registry,
;; delivers the cancelled reply) and the request is issued afterwards, with a
;; post-`sendAsync` `.cancel` standing in for not having sent it. Cancelling a
;; future cannot un-send a POST, so the assertion is inverted here rather than
;; widened: the abort completes at the issuance boundary and NOTHING is sent.
(deftest abort-completing-at-issuance-boundary-issues-no-request
  (testing "rf2-rsv2n (audit residual) — an abort injected at :issue/before-send COMPLETES before the host call; because the commit is the host call itself, the abort wins the issuance cell and jvm-fetch is NEVER entered — zero host calls, one cancelled reply, clean registry"
    (let [fetch-calls  (atom 0)
          returned-cf  (atom nil)
          abort-result (atom ::not-fired)
          replies      (atom [])]
      (try
        (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_]
                                                (swap! fetch-calls inc)
                                                (let [cf (CompletableFuture.)]
                                                  (reset! returned-cf cf)
                                                  cf))]
          (register-recorder-and-issue!
            replies :issuance-boundary
            {:url "http://127.0.0.1:0/x" :method :post :body "plain"})
          ;; The seam: preparation has succeeded and the attempt is one step
          ;; from the host call. The abort runs to completion here — registry
          ;; cleared, canonical cancelled reply dispatched — so no request may
          ;; follow it.
          (rf.http.transport/set-test-interleave-hook!
            (fn [point ctx]
              (when (and (= point :issue/before-send)
                         (= :issuance-boundary (:request-id ctx))
                         (= ::not-fired @abort-result))
                (reset! abort-result
                        (rf.http.registry/abort-in-flight! :issuance-boundary :user)))))
          (rf/dispatch-sync [:issue])
          (is (true? @abort-result)
              "the injected abort resolved the live handle at the issuance boundary")
          (is (zero? @fetch-calls)
              "jvm-fetch was NEVER entered after the abort completed — the framework must not issue a request it has already told the app was cancelled")
          (is (nil? @returned-cf)
              "no transport future was ever created: there was nothing to cancel because nothing was sent")
          (await-condition! #(seq @replies))
          (is (= 1 (count @replies)) "exactly one terminal reply — the abort's")
          (let [reply (first @replies)]
            (is (= :cancelled (:status reply)))
            (is (= :rf.http/aborted (get-in reply [:error :kind])))
            (is (= :user (get-in reply [:error :reason]))))
          (is (empty? (rf.http.registry/in-flight-snapshot)))
          (is (empty? (rf.http.registry/actor-in-flight-snapshot))))
        (finally
          (rf.http.transport/set-test-interleave-hook! nil))))))

;; ---- (6) the opposite ordering: the abort waits, then cancels --------------

(deftest concurrent-abort-cannot-complete-while-host-call-is-in-flight
  (testing "rf2-rsv2n (audit residual, opposite ordering) — an abort racing an in-flight host call BLOCKS on the issuance monitor: it cannot clear the registry or deliver its reply until the call has published its future, which it then cancels. Issuance genuinely won, so exactly one host call is correct here"
    (let [fetch-calls        (atom 0)
          returned-cf        (atom nil)
          abort-result       (atom ::not-fired)
          aborter            (atom nil)
          observed-blocked   (atom ::not-observed)
          registry-in-region (atom ::not-sampled)
          replies-in-region  (atom ::not-sampled)
          replies            (atom [])]
      (with-redefs [rf.http.transport-jvm/jvm-fetch
                    (fn [_]
                      ;; This stub runs INSIDE the issuance region, standing
                      ;; in for `HttpClient.sendAsync`. A competing abort
                      ;; fired from here is racing a host call that has begun
                      ;; and not yet published its future — the window the
                      ;; audit named.
                      (swap! fetch-calls inc)
                      (let [cf (CompletableFuture.)
                            t  (Thread.
                                 ^Runnable
                                 (fn []
                                   (reset! abort-result
                                           (rf.http.registry/abort-in-flight! :handshake :user)))
                                 "rf2-rsv2n-aborter")]
                        (reset! returned-cf cf)
                        (reset! aborter t)
                        (.start t)
                        (reset! observed-blocked (await-blocked! t 2000))
                        ;; Sampled while the abort is parked on the monitor:
                        ;; it has NOT completed, so the app has not been told
                        ;; the request was cancelled.
                        (reset! registry-in-region (rf.http.registry/in-flight-snapshot))
                        (reset! replies-in-region @replies)
                        cf))]
        (register-recorder-and-issue!
          replies :handshake
          {:url "http://127.0.0.1:0/x" :method :post :body "plain"})
        (rf/dispatch-sync [:issue])
        (.join ^Thread @aborter 5000)
        (is (true? @observed-blocked)
            "the competing abort BLOCKED on the issuance monitor — host entry and the abort's cancel step are serialized")
        (is (contains? @registry-in-region :handshake)
            "while the host call was in flight the abort had not cleared the registry — it cannot complete ahead of the request it is cancelling")
        (is (empty? @replies-in-region)
            "and it had not delivered its cancelled reply either")
        (is (true? @abort-result)
            "the abort resolved the live handle once the region released")
        (is (= 1 @fetch-calls)
            "issuance won this ordering, so exactly one host call is correct — the abort is the one that waited")
        (is (some? @returned-cf))
        (is (.isCancelled ^CompletableFuture @returned-cf)
            "the future was published before the abort could complete, so the abort cancelled it")
        (await-condition! #(seq @replies))
        (is (= 1 (count @replies)) "exactly one terminal reply — the abort's")
        (let [reply (first @replies)]
          (is (= :cancelled (:status reply)))
          (is (= :rf.http/aborted (get-in reply [:error :kind]))))
        (is (empty? (rf.http.registry/in-flight-snapshot)))
        (is (empty? (rf.http.registry/actor-in-flight-snapshot)))))))
