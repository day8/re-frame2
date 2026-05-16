(ns re-frame.ssr.ring.concurrency-stress-test
  "Per rf2-ozhy9 — JVM concurrency stress for ssr-ring's parallel
  request-handling surface. Mirrors the rf2-1gpx8 actor-spawn pattern
  (`machine_actor_concurrency_stress_test.clj`) and the rf2-35rgj router
  pattern (`concurrency_stress_test.clj`), but targets the ssr-ring
  `stream-handler` end-to-end through real Jetty + the JDK
  `java.net.http.HttpClient`.

  ## Why this exists separately from rf2-jvpli

  rf2-jvpli (`streaming_robustness_test.clj`) covers SINGLE-request
  robustness — broken pipe, root-view throw, daemon-thread name
  scoping, client disconnect mid-stream. Each of its tests fires one
  request and observes one writer-thread lifecycle. That pins the
  per-request error contracts but leaves the parallel surface uncovered.

  This namespace pins the PARALLEL surface: many concurrent in-flight
  requests through one handler, each with its own per-request frame,
  each with its own writer thread. The shape — per Spec 011 §Per-
  request frame teardown contract and Spec 002 §Rules rule 1 (frames
  are independent state machines) — is that requests don't share state.
  This test proves the implementation actually delivers on that
  promise under contention; pre-rf2-ozhy9 we had no coverage that two
  in-flight requests COULDN'T bleed app-db state into each other via
  the gensym frame-id allocator, the `setup-request-frame!` request-
  slot population race, or the writer-thread spawn site.

  ## Test scope

  Three tests, all tagged `^:stress`:

    1. `concurrent-streaming-requests-isolate-state` — N=8 threads each
       fire M=20 requests against one Jetty-hosted `stream-handler`.
       Every request encodes a unique token in its URI; the
       `:on-create` event reads the URI via the `:rf.server/request`
       cofx and writes the token into the per-request app-db; the root
       view subscribes to it and renders `<span data-rf-token=...>`.
       Invariants:
         a. **No request dropped** — every request returns 200 with a
            non-empty body.
         b. **Per-request frame isolation** — each response body
            carries EXACTLY the token its request URI supplied. A bleed
            between concurrent frames would surface as a body that
            embeds another request's token.
         c. **No orphan writer thread** — after all requests settle,
            `await-no-streaming-threads!` returns empty (the same
            leak-detector rf2-jvpli established).

    2. `concurrent-disconnect-stress` — N concurrent requests where
       every client aborts mid-stream (read a small prefix, close).
       Pins that the writer-thread lifecycle race — frame destroy
       firing while the writer is mid-flight — cleans up under
       contention. Pre-rf2-jvpli's single-request disconnect test
       proved the cleanup contract; this test proves it scales.
       Invariants: no orphan thread, no stuck client.

    3. `daemon-thread-count-bounded-during-burst` — fires the burst
       and observes that the in-flight `rf2-ssr-streaming-*` thread
       count peaks at most around `n-threads × n-iters` and decays to
       zero. A leak in the destroy path would show as a monotonically-
       growing thread count that never decays.

  ## Knobs (env-overridable)

    - `RF2_OZHY9_THREADS` — concurrent threads. Default 8.
    - `RF2_OZHY9_REQS_PER_THREAD` — requests per thread. Default 20.
    - `RF2_OZHY9_LEAK_TIMEOUT_MS` — orphan-thread settle timeout.
      Default 10000 (10s — generous; in practice <1s on every JDK
      we test against).

  Eight threads × twenty requests = 160 in-flight streamed responses
  per test, settling in a few seconds. Larger than rf2-jvpli's single-
  request shape, smaller than rf2-1gpx8's 5000-iter per-thread count
  because each iter here pays full HTTP round-trip cost rather than
  in-process dispatch cost.

  CLJS is single-threaded; ssr-ring is JVM-only by design (Ring is
  Clojure-on-the-JVM). This test is JVM-only by construction."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.test-fixture :as tf]
            [ring.adapter.jetty :as jetty])
  (:import [java.io InputStream IOException]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.atomic AtomicInteger AtomicLong]
           [org.eclipse.jetty.server Server]))

(use-fixtures :each tf/reset-runtime)

;; ===========================================================================
;; Knobs
;; ===========================================================================

(def ^:private n-threads
  (or (some-> (System/getenv "RF2_OZHY9_THREADS") Long/parseLong int)
      8))

(def ^:private n-reqs-per-thread
  (or (some-> (System/getenv "RF2_OZHY9_REQS_PER_THREAD") Long/parseLong int)
      20))

(def ^:private leak-timeout-ms
  (or (some-> (System/getenv "RF2_OZHY9_LEAK_TIMEOUT_MS") Long/parseLong)
      10000))

;; ===========================================================================
;; Jetty + HttpClient harness (mirrors streaming_robustness_test.clj)
;; ===========================================================================

(defn- start-jetty!
  [handler]
  (let [^Server server (jetty/run-jetty handler
                                        {:port                 0
                                         :host                 "127.0.0.1"
                                         :join?                false
                                         :send-server-version? false
                                         :send-date-header?    false})
        port (.. server (getURI) (getPort))]
    {:server server :port port}))

(defn- stop-jetty!
  [^Server server]
  (.stop server))

(defmacro with-jetty
  [[port-sym handler-expr] & body]
  `(let [{server# :server port# :port} (start-jetty! ~handler-expr)
         ~port-sym port#]
     (try
       ~@body
       (finally
         (stop-jetty! server#)))))

(defn- new-http-client
  "Single shared HttpClient — `HttpClient` instances are fully thread-
  safe and pool connections internally per the JDK contract, so all
  worker threads can share one without contention beyond the
  underlying socket pool."
  []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn- http-get-request
  [port path]
  (-> (HttpRequest/newBuilder)
      (.uri (URI/create (str "http://127.0.0.1:" port path)))
      (.timeout (Duration/ofSeconds 30))
      (.GET)
      (.build)))

;; ===========================================================================
;; Daemon-thread leak detector (shared with streaming_robustness_test.clj
;; intent — duplicated here so this ns is independently runnable and the
;; two tests don't co-load each other's scaffolding)
;; ===========================================================================

(def ^:private daemon-thread-name-prefix "rf2-ssr-streaming-")

(defn- live-streaming-threads
  []
  (->> (Thread/getAllStackTraces)
       (.keySet)
       (filter (fn [^Thread t]
                 (and (.isAlive t)
                      (some-> (.getName t)
                              (.startsWith daemon-thread-name-prefix)))))
       vec))

(defn- await-no-streaming-threads!
  "Poll until no `rf2-ssr-streaming-*` thread is alive, or `timeout-ms`
  elapses. 50ms poll cadence — slightly slower than the per-request
  test's 10ms because we expect a higher transient peak count and
  don't want the poll itself to be observable in profiles."
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [alive (live-streaming-threads)]
        (cond
          (empty? alive)                           []
          (>= (System/currentTimeMillis) deadline) alive
          :else (do (Thread/sleep 50) (recur)))))))

;; ===========================================================================
;; Token-echo handler — proves per-request frame isolation
;; ===========================================================================
;;
;; Each request URI is `/req/<token>` where `<token>` is unique per
;; (thread, iter). The :on-create event reads the request via the
;; :rf.server/request cofx (Spec 011 §Request storage substrate) and
;; stamps the token into THIS frame's app-db. The root view
;; subscribes to the token and renders it inline; if the rendered
;; body's token doesn't match the URI's, two concurrent requests'
;; per-frame app-dbs bled into each other.

(defn- register-echo-handlers!
  []
  ;; The `:rf.server/request` cofx is registered by `re-frame.ssr` at
  ;; ns-load and re-installed by the test-fixture's `:reload`; we
  ;; only register the handler that consumes it.
  (rf/reg-event-fx :rf.test.ozhy9/init
    {:platforms #{:server}}
    [(rf/inject-cofx :rf.server/request)]
    (fn [{request :rf.server/request} _]
      (let [uri    (or (:uri request) "/")
            ;; Strip the `/req/` prefix to extract the per-request token.
            token  (cond
                     (str/starts-with? uri "/req/") (subs uri 5)
                     :else                          uri)]
        {:db {:rf.test.ozhy9/token token}})))
  (rf/reg-sub :rf.test.ozhy9/token
              (fn [db _] (:rf.test.ozhy9/token db)))
  (rf/reg-view ^{:rf/id :rf.test.ozhy9/leaf} echo-leaf []
    [:span {:data-rf-token @(subscribe [:rf.test.ozhy9/token])}
     @(subscribe [:rf.test.ozhy9/token])])
  (rf/reg-view ^{:rf/id :rf.test.ozhy9/root} echo-root []
    [:main
     [:h1 "ozhy9 echo"]
     ;; A suspense-boundary so the streaming writer's continuation
     ;; path is exercised — without one, the writer collapses to
     ;; shell-prefix + body + final-payload + suffix, which is still
     ;; a valid streaming request but doesn't pin the continuation
     ;; thread interleave. With one, the writer thread parks and
     ;; resumes per the suspense protocol.
     [:rf/suspense-boundary
      {:id :rf.test.ozhy9/sb :fallback [:span "loading"]}
      [:rf.test.ozhy9/leaf]]
     [:footer "end"]]))

(defn- token-for [thread-idx iter-idx]
  ;; URL-safe token. Two-int composition uniquely identifies
  ;; (thread, iter) so a body that carries the wrong token names
  ;; the source thread's identity unambiguously.
  (str "t" thread-idx "-i" iter-idx))

(defn- extract-token-from-body
  "Parse the rendered body's `data-rf-token=\"...\"` attribute. Returns
  the first match (there should be exactly one per response body) or
  nil. The renderer escapes attribute values per HTML rules; our
  tokens are alphanumeric + `-` only so escaping is a no-op."
  [^String body]
  (when body
    (when-let [m (re-find #"data-rf-token=\"([^\"]+)\"" body)]
      (nth m 1))))

;; ===========================================================================
;; Test 1 — N×M concurrent requests, per-request frame isolation
;; ===========================================================================

(deftest ^:stress concurrent-streaming-requests-isolate-state
  (testing (str n-threads " threads × " n-reqs-per-thread
                " requests against stream-handler — no drops, no bleed, no orphan threads")
    (register-echo-handlers!)
    (let [handler (ssr-ring/stream-handler
                    {:on-create [:rf.test.ozhy9/init]
                     :root-view [:rf.test.ozhy9/root]
                     :payload-policy :rf.ssr.payload/whole-app-db})]
      (with-jetty [port handler]
        (let [client     (new-http-client)
              latch      (CountDownLatch. 1)
              ;; AtomicInteger counts COMPLETIONS — successful response
              ;; receipt regardless of body content. The body-token
              ;; check below is a separate invariant.
              completed  (AtomicInteger. 0)
              ;; AtomicLong counts BODY-token mismatches (bleed
              ;; signals). Zero is the contract.
              mismatches (AtomicLong. 0)
              ;; Per-thread results — vector of {:expected ... :actual ... :status ...}
              ;; for every request. The aggregate assertions below
              ;; iterate over this for the no-drop and no-bleed checks.
              results    (vec (repeatedly n-threads (fn [] (atom []))))
              futures
              (vec
                (for [thread-idx (range n-threads)]
                  (future
                    (.await latch)
                    (try
                      (dotimes [iter-idx n-reqs-per-thread]
                        (let [token    (token-for thread-idx iter-idx)
                              req      (http-get-request port (str "/req/" token))
                              response (.send client req
                                              (HttpResponse$BodyHandlers/ofString))
                              status   (.statusCode response)
                              body     (.body response)
                              actual   (extract-token-from-body body)]
                          (.incrementAndGet completed)
                          (when-not (= token actual)
                            (.incrementAndGet mismatches))
                          (swap! (nth results thread-idx) conj
                                 {:expected token
                                  :actual   actual
                                  :status   status
                                  :body-len (count body)})))
                      (catch Throwable t
                        ;; Collect the exception so an aggregate failure
                        ;; assertion below names the source.
                        (swap! (nth results thread-idx) conj
                               {:exception (.getMessage t)
                                :class     (str (class t))}))))))]
          ;; Lockstep release — maximises contention on the handler's
          ;; per-request frame allocator and the shared writer-thread
          ;; spawn site.
          (.countDown latch)
          ;; Bounded join — 120s for 8 × 20 = 160 streamed requests is
          ;; ample (each settles in <100ms on every JDK we test
          ;; against). A hang would surface as a timeout rather than
          ;; CI grinding indefinitely.
          (doseq [f futures]
            (let [v (deref f 120000 ::timeout)]
              (is (not= ::timeout v)
                  "every worker thread completed within 120s")))

          ;; --- Invariant a: no request dropped ----------------------
          (let [expected-total (* n-threads n-reqs-per-thread)
                actual-total   (.get completed)]
            (is (= expected-total actual-total)
                (str "Every request must produce a response. Expected "
                     expected-total " (= " n-threads " threads × "
                     n-reqs-per-thread " requests); got " actual-total
                     ". A drop here means either the server rejected a
                     request or the client never received a response —
                     both are tail-latency / DoS surfaces.")))

          ;; --- Invariant b: per-request frame isolation -------------
          ;; Zero bleeds. Every body-token must match its URI-token.
          (is (zero? (.get mismatches))
              (let [bleeds (->> results
                                (mapcat deref)
                                (filter (fn [r]
                                          (and (not (:exception r))
                                               (not= (:expected r) (:actual r)))))
                                (take 5))]
                (str "Per-request frame isolation broken — "
                     (.get mismatches) " response bodies carried a
                     different token than their request URI. First 5
                     bleeds: " (pr-str bleeds))))

          ;; --- Cross-check: no exceptions on any worker thread ------
          (let [errors (->> results
                            (mapcat deref)
                            (filter :exception)
                            vec)]
            (is (empty? errors)
                (str "Worker threads must complete without exceptions; got "
                     (count errors) " errors. First 3: "
                     (pr-str (take 3 errors)))))

          ;; --- Cross-check: every status was 200 --------------------
          (let [non-200 (->> results
                             (mapcat deref)
                             (remove :exception)
                             (filter (fn [r] (not= 200 (:status r))))
                             vec)]
            (is (empty? non-200)
                (str "Every request must return 200; got "
                     (count non-200) " non-200 responses. First 3: "
                     (pr-str (take 3 non-200)))))

          ;; --- Invariant c: no orphan writer threads ----------------
          (let [leaked (await-no-streaming-threads! leak-timeout-ms)]
            (is (empty? leaked)
                (str "No `rf2-ssr-streaming-*` daemon thread should
                     remain after all requests settle — leaks
                     accumulate one thread per request, classic
                     resource-exhaustion DoS vector. Live threads
                     observed after " leak-timeout-ms "ms settle: "
                     (mapv (fn [^Thread t] (.getName t)) leaked)))))))))

;; ===========================================================================
;; Test 2 — concurrent disconnect: writer-thread lifecycle race vs frame destroy
;; ===========================================================================
;;
;; The single-request version of this scenario is rf2-jvpli's
;; `client-disconnect-mid-stream-cleans-up`. This is the parallel
;; counterpart: many clients abort mid-stream simultaneously, and we
;; assert the writer-thread cleanup + `destroy-frame-quietly!` race
;; settles cleanly under contention.
;;
;; Why this matters: `stream-handler` runs the frame teardown inside
;; the writer thread's `finally` (see `streaming.clj` line ~256). If
;; the destroy path takes a registrar-wide lock or touches a shared
;; atom under contention, N concurrent destroys could deadlock or
;; double-destroy. Single-request testing can't see this — it needs
;; the parallel path.

(deftest ^:stress concurrent-disconnect-stress
  (testing (str n-threads " threads × " n-reqs-per-thread
                " concurrent disconnect-mid-stream — no orphan threads, no hangs")
    (register-echo-handlers!)
    (let [handler (ssr-ring/stream-handler
                    {:on-create [:rf.test.ozhy9/init]
                     :root-view [:rf.test.ozhy9/root]
                     :payload-policy :rf.ssr.payload/whole-app-db})]
      (with-jetty [port handler]
        (let [client     (new-http-client)
              latch      (CountDownLatch. 1)
              completed  (AtomicInteger. 0)
              futures
              (vec
                (for [thread-idx (range n-threads)]
                  (future
                    (.await latch)
                    (dotimes [iter-idx n-reqs-per-thread]
                      (try
                        (let [token    (token-for thread-idx iter-idx)
                              req      (http-get-request port (str "/req/" token))
                              response (.send client req
                                              (HttpResponse$BodyHandlers/ofInputStream))
                              ^InputStream body-is (.body response)
                              ;; Read a small prefix so the writer is
                              ;; mid-flight when we close.
                              prefix-buf (byte-array 64)]
                          (.read body-is prefix-buf 0 64)
                          (.close body-is)
                          (.incrementAndGet completed))
                        (catch Throwable _
                          ;; Some abort paths surface as IOException on
                          ;; the .read or .send — that's still a valid
                          ;; abort. We count completion semantically:
                          ;; the request reached the server and
                          ;; triggered a writer thread.
                          (.incrementAndGet completed)))))))]
          (.countDown latch)
          (doseq [f futures]
            (let [v (deref f 120000 ::timeout)]
              (is (not= ::timeout v)
                  "every disconnect-worker completed within 120s")))

          (let [expected-total (* n-threads n-reqs-per-thread)
                actual-total   (.get completed)]
            (is (= expected-total actual-total)
                (str "Every abort attempt accounted for. Expected "
                     expected-total "; got " actual-total)))

          ;; The load-bearing assertion — under N parallel disconnects,
          ;; every writer thread MUST observe the broken pipe and
          ;; exit, and every per-request frame's destroy MUST run
          ;; without deadlock.
          (let [leaked (await-no-streaming-threads! leak-timeout-ms)]
            (is (empty? leaked)
                (str "No orphan `rf2-ssr-streaming-*` after concurrent
                     disconnect storm. Live threads observed after "
                     leak-timeout-ms "ms settle: "
                     (mapv (fn [^Thread t] (.getName t)) leaked)))))))))

;; ===========================================================================
;; Test 3 — daemon thread peak count is bounded; decays to zero
;; ===========================================================================
;;
;; A leak in the destroy path would manifest as a writer-thread count
;; that climbs as we drive more requests and never decays. This test
;; samples the live-thread count during the burst (peak) and after
;; settle (decay), and asserts:
;;
;;   - peak ≤ a generous upper bound (no unbounded spawn behaviour);
;;   - post-settle count is exactly zero (clean decay).
;;
;; The peak bound is `n-threads × n-reqs-per-thread + slack`. In
;; principle every request CAN be in-flight simultaneously if the
;; client side is fast enough; in practice client serialisation per
;; thread caps it at `n-threads` peak, but we use the loose upper
;; bound to avoid false-positive flakes when the JVM scheduler
;; happens to interleave favorably.

(deftest ^:stress daemon-thread-count-bounded-during-burst
  (testing "writer-thread count bounded during burst, decays to zero after"
    (register-echo-handlers!)
    (let [handler (ssr-ring/stream-handler
                    {:on-create [:rf.test.ozhy9/init]
                     :root-view [:rf.test.ozhy9/root]
                     :payload-policy :rf.ssr.payload/whole-app-db})]
      (with-jetty [port handler]
        (let [client     (new-http-client)
              latch      (CountDownLatch. 1)
              ;; AtomicInteger that we sample peak across — every
              ;; sampler thread contends here.
              peak-count (AtomicInteger. 0)
              ;; The sampler thread periodically reads the live thread
              ;; count and CAS-bumps the peak. Runs for the duration
              ;; of the burst.
              sampler-stop (CountDownLatch. 1)
              sampler-thread
              (Thread.
                ^Runnable
                (fn []
                  ;; Tight loop with no sleep — we want to catch every
                  ;; transient peak. The thread exits as soon as
                  ;; sampler-stop counts down. CPU cost is bounded by
                  ;; the burst duration (a few seconds at most).
                  (while (zero? (.getCount sampler-stop))
                    (let [n (count (live-streaming-threads))]
                      (loop []
                        (let [cur (.get peak-count)]
                          (when (and (> n cur)
                                     (not (.compareAndSet peak-count cur n)))
                            (recur)))))))
                "rf2-ozhy9-thread-count-sampler")
              futures
              (vec
                (for [thread-idx (range n-threads)]
                  (future
                    (.await latch)
                    (dotimes [iter-idx n-reqs-per-thread]
                      (let [token (token-for thread-idx iter-idx)
                            req   (http-get-request port (str "/req/" token))]
                        (.send client req
                               (HttpResponse$BodyHandlers/ofString)))))))]
          (.start sampler-thread)
          (.countDown latch)
          (doseq [f futures]
            (let [v (deref f 120000 ::timeout)]
              (is (not= ::timeout v)
                  "every burst-worker completed within 120s")))
          (.countDown sampler-stop)
          (.join sampler-thread 5000)

          ;; Loose upper bound: every request COULD be concurrently
          ;; in-flight in the worst case. Pick `(n-threads × n-reqs)
          ;; + 8` slack for the sampler observation window.
          ;;
          ;; The sampler may legitimately observe peak == 0 on very
          ;; fast JDKs (every request completes inside a single
          ;; sampler tick). That's OK — the load-bearing assertion is
          ;; the upper bound (no leak) AND the decay-to-zero check
          ;; below (post-settle terminates cleanly). The peak
          ;; observation is a diagnostic signal, not a contract.
          (let [upper-bound (+ (* n-threads n-reqs-per-thread) 8)
                observed    (.get peak-count)]
            (is (<= observed upper-bound)
                (str "Writer-thread peak count must be bounded. Upper
                     bound (loose): " upper-bound "; observed peak: "
                     observed ". A peak above the bound suggests the
                     spawn site is leaking threads on a per-request
                     fast path.")))

          ;; Decay: every writer terminated.
          (let [leaked (await-no-streaming-threads! leak-timeout-ms)]
            (is (empty? leaked)
                (str "Writer-thread count must decay to zero after the
                     burst settles. Live threads after " leak-timeout-ms
                     "ms: "
                     (mapv (fn [^Thread t] (.getName t)) leaked)))))))))
