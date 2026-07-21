(ns re-frame.http-abort-retry-race-test
  "JVM concurrency regression for the abort-vs-retry-vs-successor race
  (rf2-6nczv9 + rf2-ous9e5, http correctness audit rf2-k0pa70).

  These are JVM-only races (CLJS is single-threaded and immune): on the JVM
  the request completion runs on a `CompletableFuture` ForkJoinPool thread
  while the abort dispatch runs on the event thread, so they can interleave
  mid-`maybe-retry!`.

  The transitional window is the instant between `maybe-retry!` sampling the
  abort cell (deciding the failure is retry-eligible) and the backoff handle
  taking over the request-id slot. An abort that lands there used to:
   - DOUBLE-REPLY (the prior handle's abort-fn replied, then the freshly-armed
     retry replied again), and
   - RE-ISSUE a fresh network attempt for a request the caller cancelled
     (Spec 014 §486-492).
  The narrower sibling — an abort between the prior clear and the backoff
  re-register — resolved NO handle and was silently lost, and the retry fired
  anyway.

  Deterministic repro needs INTERLEAVING INJECTION, not wall-clock timing (the
  existing `http_backoff_cancellation_test` fires 150ms into the settled
  backoff, which correctly cancels the steady-state handle — not this window).
  We drive the interleaving through `transport/set-test-interleave-hook!`, a
  test-only seam that fires the abort SYNCHRONOUSLY at a named point inside the
  transition (on the completion thread, while the prior handle is still
  registered), reproducing the exact ordering the audit described.

  Spec references:
   - Spec 014 §Abort precedence (abort always wins), §486-492
   - Spec 014 §Retry and backoff / §Aborts

  Coverage:
   1. abort injected in `maybe-retry!` (post-abort-snapshot, pre-schedule)
      → exactly one :rf.http/aborted reply, ZERO extra server hits.
   2. abort injected at the START of `schedule-backoff-handle!`
      (the narrower-sibling window) → same: one aborted reply, no retry.
   3. rf2-ous9e5 unit: `clear-in-flight!` (2-arg) is identity-conditional —
      a completing OLD attempt does NOT evict a same-id SUCCESSOR's handle."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as registry]
            [re-frame.http.transport :as transport]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- hit-counting always-500 server ---------------------------------------

(defn- start-counting-500-server!
  []
  (let [hits   (AtomicInteger. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (.incrementAndGet hits)
                        (let [^HttpExchange ex ex
                              bs (.getBytes "boom" "UTF-8")]
                          (try
                            (.sendResponseHeaders ex 500 (long (count bs)))
                            (with-open [os (.getResponseBody ex)]
                              (.write os bs))
                            (catch Throwable _ nil))))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))
     :hits   hits}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

;; A long-enough backoff that a re-issue (if the bug were live) would land
;; well inside the observation window.
(def ^:private backoff-ms 2000)

(def ^:private retry-config
  {:on           #{:rf.http/http-5xx}
   :max-attempts 5
   :backoff      {:base-ms backoff-ms :factor 1 :max-ms backoff-ms}})

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-abort-retry-race condition"})
   true))

;; ---- shared driver ---------------------------------------------------------

(defn- run-injected-abort-case!
  "Issue a retrying request against an always-500 server; install the
  interleaving hook so the abort fires (once) at `inject-point`, squarely in
  the transitional window. Assert: exactly ONE :rf.http/aborted reply, the
  server was hit exactly ONCE (no re-issue), and the registry is clean.

  rf2-fyt5i — ALSO assert HONEST retry-timeline dispositions: because the
  request is aborted in-window and attempt N+1 never starts, the trace stream
  must carry NO `:rf.http/retry-attempt` claiming `:recovery :retried`. Before
  the fix the intermediate `:retried` emit was ordered ahead of the
  cancellation-safe backoff handoff, so a listener saw a `:retried` row (with
  non-nil `:next-backoff-ms`) for a retry that never happened — a tool could
  report a retry the runtime did not perform."
  [inject-point]
  (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
        replies     (atom [])
        traces      (atom [])
        listener-id ::retry-timeline-honesty
        fired?      (atom false)]
    (try
      (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      (rf/reg-event :issue
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                  :decode     :json
                  :retry      retry-config
                  :request-id :race
                  :on-failure [:reply/recorder]
                  :on-success [:reply/recorder]}]]}))
      ;; The seam: fire the abort exactly once, when the retry lifecycle
      ;; reaches `inject-point`, resolving the still-registered prior handle.
      (transport/set-test-interleave-hook!
        (fn [point ctx]
          (when (and (= point inject-point)
                     (= :race (:request-id ctx))
                     (compare-and-set! fired? false true))
            ;; Same seam the `:rf.http/managed-abort` fx routes through.
            (registry/abort-in-flight! (:request-id ctx) :user))))
      (rf/dispatch-sync [:issue])
      ;; The abort's reply must arrive.
      (await-condition! #(seq @replies))
      (is (true? @fired?) "the interleaving hook fired the abort in-window")
      ;; Wait past the FULL backoff window — proving the ABSENCE of a re-issue,
      ;; which has no positive signal to poll on.
      (Thread/sleep (long (+ backoff-ms 600)))
      (is (= 1 (.get hits))
          "ZERO extra server hits — the cancelled request was NEVER re-issued")
      (is (= 1 (count @replies))
          "EXACTLY ONE terminal reply — no double-reply")
      (let [reply (first @replies)]
        (is (= :cancelled (:status reply))
            "the single reply is the cancellation")
        (is (= :rf.http/aborted (get-in reply [:error :kind]))
            "the single reply is the canonical :rf.http/aborted")
        (is (= :user (get-in reply [:error :reason]))))
      (is (empty? (registry/in-flight-snapshot))
          "the request-id registry is clean after the cancelled transition")
      (is (empty? (registry/actor-in-flight-snapshot))
          "the actor registry is clean")
      ;; rf2-fyt5i — the honesty assertion (red before the fix): a retry that
      ;; NEVER started must not surface a `:retried` disposition. The abort
      ;; fired in-window and no attempt N+1 ran, so the trace stream carries
      ;; ZERO `:rf.http/retry-attempt` events claiming `:recovery :retried`.
      (let [retried (filter #(and (= :rf.http/retry-attempt (:operation %))
                                  (= :retried (:recovery %)))
                            @traces)]
        (is (empty? retried)
            (str "an in-window cancellation with zero reissue must NOT emit a "
                 "phantom `:rf.http/retry-attempt` `:recovery :retried`; saw "
                 (pr-str (mapv (fn [ev] {:recovery (:recovery ev)
                                         :next-backoff-ms (get-in ev [:tags :next-backoff-ms])})
                               retried)))))
      (finally
        (trace/unregister-listener! listener-id)
        (transport/set-test-interleave-hook! nil)
        (stop-server! srv)))))

;; ---- (1) abort in maybe-retry!'s transitional window -----------------------

(deftest abort-injected-in-maybe-retry-window-no-double-reply-no-reissue
  (testing "rf2-6nczv9 — an abort landing after maybe-retry!'s abort-snapshot but before the backoff registers yields exactly one :rf.http/aborted reply and no fresh network attempt"
    (run-injected-abort-case! :maybe-retry/before-schedule)))

;; ---- (2) narrower-sibling window (start of schedule-backoff-handle!) --------

(deftest abort-injected-at-backoff-registration-boundary-no-reissue
  (testing "rf2-6nczv9 (narrower sibling) — an abort landing at the backoff registration boundary (prior clear now deferred, so the prior handle is still resolvable) yields one aborted reply and no retry"
    (run-injected-abort-case! :backoff/before-register)))

;; ---- (3) rf2-ous9e5 — identity-conditional clear-in-flight! ----------------

(deftest clear-in-flight-2arg-is-identity-conditional
  (testing "rf2-ous9e5 — clear-in-flight! (2-arg) does NOT evict a same-id successor's live handle; a completing OLD attempt clearing by its own handle leaves the successor in place"
    (registry/clear-all-in-flight!)
    (let [h-a (registry/seed-in-flight-for-test! :R nil {:abort-fn (fn [_] nil) :url "a"})
          h-b (registry/seed-in-flight-for-test! :R nil {:abort-fn (fn [_] nil) :url "b"})]
      ;; H_B (the successor) took over the request-id slot.
      (is (identical? h-b (get (registry/in-flight-snapshot) :R))
          "the successor H_B is the live occupant under :R")
      ;; The OLD attempt (H_A) completes and clears BY ITS OWN handle.
      (registry/clear-in-flight! :R h-a)
      (is (identical? h-b (get (registry/in-flight-snapshot) :R))
          "the successor H_B SURVIVES — a stale completion must not evict it (the pre-fix unconditional dissoc did)")
      ;; Single-request cleanup is unchanged: clearing by the LIVE handle removes it.
      (registry/clear-in-flight! :R h-b)
      (is (not (contains? (registry/in-flight-snapshot) :R))
          "clearing by the live handle removes the slot exactly as before"))
    (registry/clear-all-in-flight!)))

;; ---- (4) actor-index identity clear survives a successor -------------------

(deftest clear-in-flight-2arg-preserves-successor-actor-index
  (testing "rf2-ous9e5 — an OLD attempt's identity-conditional clear does not evict a same-id successor even when actor-indexed (mirrors remove-from-actor-index!)"
    (registry/clear-all-in-flight!)
    (let [h-a (registry/seed-in-flight-for-test! :R :actor {:abort-fn (fn [_] nil) :url "a"})
          h-b (registry/seed-in-flight-for-test! :R :actor {:abort-fn (fn [_] nil) :url "b"})]
      (is (identical? h-b (get (registry/in-flight-snapshot) :R)))
      (registry/clear-in-flight! :R h-a)
      (is (identical? h-b (get (registry/in-flight-snapshot) :R))
          "successor survives in the request-id index")
      ;; H_A is removed from the actor vector (by identity); H_B remains.
      (let [v (get (registry/actor-in-flight-snapshot) :actor)]
        (is (some #(identical? % h-b) v) "H_B still actor-indexed")
        (is (not (some #(identical? % h-a) v)) "H_A dropped from the actor index by identity")))
    (registry/clear-all-in-flight!)))
