(ns re-frame.http-abort-retry-race-test
  "JVM-only races between an abort, a retry and a same-id successor: the
  completion runs on a ForkJoinPool thread while the abort runs on the event
  thread, so they can interleave inside a retry handoff. There are two
  handoffs, live fetch to backoff and backoff timer to attempt N+1. An abort
  landing inside either must yield one aborted reply and no re-issue (Spec 014
  §Abort precedence, §Aborts), and a successor issued inside either must keep
  its request-id slot. `transport/set-test-interleave-hook!` fires the abort
  or the successor synchronously at a named point inside the handoff, so the
  ordering is injected rather than timed."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport :as rf.http.transport]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch Executors TimeUnit]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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

;; Long enough that a re-issue would land well inside the observation window.
(def ^:private backoff-ms 2000)

(def ^:private retry-config
  {:on           #{:rf.http/http-5xx}
   :max-attempts 5
   :backoff      {:base-ms backoff-ms :factor 1 :max-ms backoff-ms}})

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-abort-retry-race condition"})
   true))

(defn- run-injected-abort-case!
  "Issue a retrying request against an always-500 server and fire the abort
  once at `inject-point`. Every window must give the same outcome: one aborted
  reply, one server hit, clean registries, and no phantom `:retried`
  retry-attempt row for an attempt that never ran."
  [inject-point]
  (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
        replies     (atom [])
        traces      (atom [])
        listener-id ::retry-timeline-honesty
        fired?      (atom false)]
    (try
      (rf.trace.tooling/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
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
      (rf.http.transport/set-test-interleave-hook!
        (fn [point ctx]
          (when (and (= point inject-point)
                     (= :race (:request-id ctx))
                     (compare-and-set! fired? false true))
            (rf.http.registry/abort-in-flight! (:request-id ctx) :user))))
      (rf/dispatch-sync [:issue])
      (await-condition! #(seq @replies))
      (is (true? @fired?) "the hook fired the abort in-window")
      ;; A re-issue has no positive signal to poll on: wait out the backoff.
      (Thread/sleep (long (+ backoff-ms 600)))
      (is (= 1 (.get hits)) "the cancelled request was never re-issued")
      (is (= [[:cancelled :rf.http/aborted :user]]
             (mapv (juxt :status (comp :kind :error) (comp :reason :error)) @replies)))
      (is (and (empty? (rf.http.registry/in-flight-snapshot))
               (empty? (rf.http.registry/actor-in-flight-snapshot))))
      (is (empty? (filter #(and (= :rf.http/retry-attempt (:operation %))
                                (= :retried (:recovery %)))
                          @traces))
          "no phantom :retried row")
      (finally
        (rf.trace.tooling/unregister-listener! listener-id)
        (rf.http.transport/set-test-interleave-hook! nil)
        (stop-server! srv)))))

(deftest abort-injected-in-maybe-retry-window-no-double-reply-no-reissue
  ;; After maybe-retry!'s abort snapshot, before the backoff registers.
  (run-injected-abort-case! :maybe-retry/before-schedule))

(deftest abort-injected-at-backoff-registration-boundary-no-reissue
  ;; At the backoff registration boundary; the prior clear is deferred, so the
  ;; prior handle is still resolvable.
  (run-injected-abort-case! :backoff/before-register))

(deftest abort-injected-at-timer-fire-handoff-no-reissue-no-phantom-retried
  ;; After the timer won `fired?`, before the successor registers: the abort
  ;; resolves the predecessor handle, and run-attempt!'s post-registration
  ;; re-check delivers the reply and suppresses the fresh attempt.
  (run-injected-abort-case! :retry/before-attempt))

(deftest clear-in-flight-2arg-preserves-successor-actor-index
  ;; An OLD attempt's identity-conditional clear evicts neither a same-id
  ;; successor's request-id slot nor its actor-index entry.
  (rf.http.registry/clear-all-in-flight!)
  (let [h-a (rf.http.registry/seed-in-flight-for-test! :R :actor {:abort-fn (fn [_] nil) :url "a"})
        h-b (rf.http.registry/seed-in-flight-for-test! :R :actor {:abort-fn (fn [_] nil) :url "b"})]
    (rf.http.registry/clear-in-flight! :R h-a)
    (let [v (get (rf.http.registry/actor-in-flight-snapshot) :actor)]
      (is (= [true true false]
             [(identical? h-b (get (rf.http.registry/in-flight-snapshot) :R))
              (boolean (some #(identical? % h-b) v))
              (boolean (some #(identical? % h-a) v))]))))
  (rf.http.registry/clear-all-in-flight!))

;; R1 gets a 500 and hands off into its retry. At `inject-point` — squarely
;; inside a handoff, on R1's own thread — the interleaving hook issues the
;; same-id successor R2 through the real fx body, exactly as the event thread
;; would: `supersede!` aborts R1 (reply suppressed) and R2 registers and goes on
;; the wire, where the server parks it. R1's handoff then resumes. It decided to
;; proceed before the supersede landed, so it must now leave R2's slot alone.

(defn- start-500-then-parked-server!
  "Hit 1 answers 500; every later hit parks on `release`, then answers 200."
  [^CountDownLatch release]
  (let [hits   (AtomicInteger. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              n  (.incrementAndGet hits)
                              _  (when (> n 1) (.await release 30 TimeUnit/SECONDS))
                              bs (.getBytes (if (= 1 n) "boom" "{}") "UTF-8")]
                          (try
                            (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
                            (.sendResponseHeaders ex (if (= 1 n) 500 200) (long (count bs)))
                            (with-open [os (.getResponseBody ex)]
                              (.write os bs))
                            (catch Throwable _ nil))))))
    ;; one thread per exchange, so the parked successor blocks nothing else
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))
     :hits   hits}))

(defn- run-successor-in-window-case!
  [inject-point]
  (let [release (CountDownLatch. 1)
        {:keys [^AtomicInteger hits] :as srv} (start-500-then-parked-server! release)
        replies (atom [])
        fired?  (atom false)
        hook-done (CountDownLatch. 1)
        args    {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                 :decode     :json
                 :retry      {:on           #{:rf.http/http-5xx}
                              :max-attempts 3
                              :backoff      {:base-ms 50 :factor 1 :max-ms 50}}
                 :request-id :race
                 :on-failure [:reply/recorder]
                 :on-success [:reply/recorder]}]
    (try
      (rf/reg-event :reply/recorder
        (fn [_ [_ p]]
          (swap! replies conj [(:status p) (get-in p [:error :reason]) (:rf.reply/work-id p)])
          {}))
      (rf/reg-event :issue (fn [_ _] {:fx [[:rf.http/managed args]]}))
      (rf/reg-event :abort (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
      (rf.http.transport/set-test-interleave-hook!
        (fn [point ctx]
          (when (and (= point inject-point)
                     (= 1 (:issuance ctx))
                     (compare-and-set! fired? false true))
            (rf.http.managed/managed-handler {:frame :rf/default :event [:issue]} args)
            (.countDown hook-done))))
      (rf/dispatch-sync [:issue])
      (is (.await hook-done 5 TimeUnit/SECONDS) "the successor was issued inside the window")
      (await-condition! #(= 2 (.get hits)))
      ;; R1's handoff resumes on its own thread the instant the hook returns and
      ;; finishes in microseconds; this bound only lets it get there.
      (Thread/sleep 300)
      (is (= 2 (:issuance (get (rf.http.registry/in-flight-snapshot :rf/default) :race)))
          "the successor R2 still holds the request-id slot after R1's handoff")
      (rf/dispatch-sync [:abort])
      (await-condition! #(seq @replies))
      (.countDown release)
      ;; the ABSENCE of a late success reply has no positive signal to poll on
      (Thread/sleep 400)
      (is (= [[:cancelled :user [:rf.work/http :race 2 1]]] @replies)
          "managed-abort reached R2 — one cancellation, and no success after it")
      (is (= 2 (.get hits)) "R1 was never re-issued")
      (is (empty? (rf.http.registry/in-flight-snapshot)) "the request-id registry is clean")
      (finally
        (.countDown release)
        (rf.http.transport/set-test-interleave-hook! nil)
        ;; On a red run R2 is unregistered, so the reset fixture cannot abort
        ;; it: let it answer here rather than into the next test's recorder.
        (Thread/sleep 300)
        (stop-server! srv)))))

(deftest successor-issued-at-backoff-registration-keeps-its-slot
  (run-successor-in-window-case! :backoff/before-register))

(deftest successor-issued-at-timer-fire-handoff-keeps-its-slot
  (run-successor-in-window-case! :retry/before-attempt))
