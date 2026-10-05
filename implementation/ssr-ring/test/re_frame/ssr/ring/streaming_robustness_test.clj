(ns re-frame.ssr.ring.streaming-robustness-test
  "Robustness coverage for the streaming SSR daemon writer thread —
  `re-frame.ssr.ring.streaming/run-streaming-writer!` and the daemon
  thread `stream-handler` spawns to invoke it.

  ## Why this lives at the daemon-thread layer

  The non-streaming SSR path is fully synchronous on the request
  thread — the only error surface is the outer `try/catch` in
  `ssr-handler` that routes throws to `:on-error`. The streaming path
  is asymmetric: the writer runs on a daemon thread the request handler
  spawned and detached. The Ring response was already returned to Jetty
  by the time the writer encounters a problem. So the writer's failure-
  mode contract is structurally different — no caller frame to bubble
  to, no `:on-error` hook to invoke (the response has already started).

  Per Spec 011 §Failure semantics — exceptions the writer thread
  owns (a final-payload build throw, a downstream
  OutputStream broken-pipe, a continuation drain) close the pipe with
  whatever partial response was flushed and emit a structured
  `:rf.error/ssr-streaming-writer-failed` trace; the writer's `finally`
  closes the OutputStream so the Ring server emits EOF; the daemon
  thread terminates. (Root-view / head / shell-walk
  resolution runs on the REQUEST thread, before the head commits — those
  fail closed to a non-200 on the request thread and never reach the
  writer.) The load-bearing contracts:

    1. The writer thread MUST NOT escape with an uncaught throwable
       (it is the top-level Runnable of a detached thread — an escaped
       throw goes to the JVM default uncaught-exception handler,
       polluting logs and providing no recovery affordance to the host
       adapter).
    2. The OutputStream MUST be closed in every exit path — open pipes
       pin a buffer, and the Ring server is waiting on EOF to finalise
       the chunked-transfer response.
    3. The daemon thread MUST terminate — leaks accumulate one thread
       per failed request, which is a textbook resource-exhaustion DoS
       vector under sustained client misbehaviour (slow loris, abrupt
       disconnects).

  ## Test scope

  Tests:

    1. `client-disconnect-mid-stream-cleans-up` — real Jetty + a real
       HTTP client that reads only the response head + a few bytes of
       the body then closes the InputStream. The writer thread, mid-
       flight on the next `.write`, hits a broken-pipe IOException;
       same catch + finally semantics; thread terminates within a
       generous bound; no orphan `rf2-ssr-streaming-*` thread remains.
    2. `daemon-thread-name-is-frame-scoped` — sanity check that the
       writer thread is named `rf2-ssr-streaming-<frame-id>` so the
       leak-detection assertions above can scope by name prefix and
       operators can correlate JFR / thread dumps to frames.

  The writer body's `catch Throwable` arm itself is driven directly, on a
  pipe closed before the first write, by `streaming_writer_trace_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.streaming :as rf.ssr.ring.streaming]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]
            [re-frame.test-support :refer [with-trace-recorder!]]
            [ring.middleware.head :as ring.head])
  (:import [java.io InputStream]
           [java.net.http HttpResponse$BodyHandlers]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

;; ===========================================================================
;; Shared test scaffolding — handlers, view registrations, helpers
;; ===========================================================================

(defn- register-baseline-handlers!
  "Seed the canonical streaming test handlers used by every test: a
  server `:initial-events` event that lays down an articles/comments
  app-db, the matching subs, and a single root-view with one
  `:rf/suspense-boundary`. Tests that need a different root override
  by re-registering `:test/root` after this runs."
  []
  (rf/reg-event :rf.test.server/init
    {:platforms #{:server}}
    (fn [_ _]
      {:db {:articles [{:id "a" :title "Article A"}
                       {:id "b" :title "Article B"}]
            :comments [{:body "First!"} {:body "Nice"}]}}))
  (rf/reg-sub :articles (fn [db _] (:articles db)))
  (rf/reg-sub :comments (fn [db _] (:comments db)))
  (rf/reg-view ^{:rf/id :test/article-list} article-list-view []
    (let [arts @(subscribe [:articles])]
      (into [:ul.articles]
            (for [{:keys [id title]} arts]
              ^{:key id} [:li title]))))
  (rf/reg-view ^{:rf/id :test/comments-section} comments-view []
    (let [cs @(subscribe [:comments])]
      (into [:ul.comments]
            (for [{:keys [body]} cs]
              [:li body]))))
  (rf/reg-view ^{:rf/id :test/root} root-view []
    [:main
     [:h1 "News"]
     [(rf/view :test/article-list)]
     [:rf/suspense-boundary
      {:id :test/comments :fallback [:p "Loading comments…"]}
      [(rf/view :test/comments-section)]]
     [:footer "End"]]))

;; ---- Jetty + JDK HTTP client + leak detector -----------------------------
;;
;; The ephemeral Jetty host, the `java.net.http` client /
;; request builder, and the `rf2-ssr-streaming-*` daemon-thread leak
;; detector live in `re-frame.ssr.ring.test-support`,
;; shared with the other live-host / streaming-thread test namespaces.
;; The per-test knobs are explicit at the call sites below: a 10s read
;; timeout (`http-get-request`/`http-get` 3rd arg) and a 10ms leak-poll
;; cadence (`await-no-streaming-threads!` 2nd arg — the single-request
;; tests poll faster than the concurrency burst's 50ms).

(def ^:private read-timeout-secs 10)
(def ^:private leak-poll-ms 10)

(defn- await-no-streaming-threads!
  "Single-request poll cadence (10ms) over the shared leak detector.
  Returns the leaked-thread vec on timeout (does not throw) so
  the assertion can name the offenders."
  [timeout-ms]
  (rf.ssr.ring.test-support/await-no-streaming-threads! timeout-ms leak-poll-ms))

;; ===========================================================================
;; Test 1 — client disconnect mid-stream: writer cleans up, no orphan thread
;; ===========================================================================
;;
;; End-to-end disconnect path. We send a real HTTP request through
;; Jetty, read JUST enough of the response body to confirm streaming
;; has started (the shell prefix + a few hundred bytes), then close
;; the response InputStream. On the next chunk write, the daemon
;; writer thread hits `IOException: Broken pipe` (or `Connection reset
;; by peer` — Jetty surface varies by JDK version; both are IOExceptions).
;;
;; The writer's catch + finally contract, observed through the real
;; transport: no orphan `rf2-ssr-streaming-*` thread after a generous
;; settle window. The settle window is needed because thread
;; termination is asynchronous w.r.t. the client read — we wait until
;; the JVM thread table reflects the writer's exit.

(deftest client-disconnect-mid-stream-cleans-up
  (testing "abrupt client disconnect → writer terminates cleanly, no orphan daemon thread"
    (register-baseline-handlers!)
    (let [handler (rf.ssr.ring/stream-handler
                    {:initial-events [[:rf.test.server/init]]
                     :root-view [(rf/view :test/root)]
                     :payload :rf.ssr.payload/whole-app-db})]
      (rf.ssr.ring.test-support/with-jetty [port handler]
        (let [client   (rf.ssr.ring.test-support/new-http-client)
              req      (rf.ssr.ring.test-support/http-get-request port "/" read-timeout-secs)
              response (.send client req (HttpResponse$BodyHandlers/ofInputStream))
              ;; Read a small prefix so we know the writer thread has
              ;; flushed the shell chunk. We don't care WHAT we read,
              ;; only that the stream has started — which guarantees
              ;; the writer thread is alive and mid-flight.
              ^InputStream body-is (.body response)
              prefix-buf           (byte-array 256)
              _read                (.read body-is prefix-buf 0 256)]
          ;; Sanity — Jetty replied 200 and the writer started.
          (is (= 200 (.statusCode response))
              "stream-handler defaults to 200 status")
          ;; Abrupt disconnect.
          (.close body-is)
          ;; The writer thread now blocks on the next .write to the
          ;; pipe; once Jetty's response writer notices the upstream
          ;; close, the pipe's read end closes too, surfacing
          ;; IOException on the next write. We wait for the daemon to
          ;; observe + terminate. 5 seconds is generous — in practice
          ;; this resolves in <100ms on every JDK we test against.
          (let [leaked (await-no-streaming-threads! 5000)]
            (is (empty? leaked)
                (str "no orphan rf2-ssr-streaming-* daemon thread after
                     client disconnect — would leak one thread per
                     aborted request, a classic resource-exhaustion DoS
                     vector. Live threads observed: "
                     (mapv (fn [^Thread t] (.getName t)) leaked)))))))))

;; ===========================================================================
;; Test 2 — daemon thread name carries the frame-id (correlation + leak scope)
;; ===========================================================================
;;
;; Sanity check that the writer thread is named `rf2-ssr-streaming-
;; <frame-id>` so:
;;
;;   - the leak-detection assertions in this namespace scope by name
;;     prefix correctly (otherwise we'd be matching every JVM thread
;;     and the contract would be untestable);
;;   - operators correlating thread dumps / JFR recordings to specific
;;     frames have a name to grep for.
;;
;; This is a "the bones of the leak detector are real" test — it
;; exists so a future refactor that renames the thread can't silently
;; defeat the orphan-detection in this namespace.

(deftest daemon-thread-name-is-frame-scoped
  (testing "writer thread name starts with rf2-ssr-streaming-"
    (register-baseline-handlers!)
    ;; A view that pauses inside a continuation render — gives us a
    ;; window where the daemon thread is alive and observable.
    (let [latch       (java.util.concurrent.CountDownLatch. 1)
          release     (java.util.concurrent.CountDownLatch. 1)
          observed    (atom nil)]
      (rf/reg-view ^{:rf/id :test/parking-section} parking-section []
        ;; While the daemon thread is parked here, scan for it.
        ;; Capture once and release.
        (.countDown latch)
        (.await release 5 java.util.concurrent.TimeUnit/SECONDS)
        [:p "released"])
      (rf/reg-view ^{:rf/id :test/parking-root} parking-root []
        [:main
         [:rf/suspense-boundary
          {:id :test/parker :fallback [:p "loading"]}
          [(rf/view :test/parking-section)]]])
      (let [handler  (rf.ssr.ring/stream-handler
                       {:initial-events [[:rf.test.server/init]]
                        :root-view [(rf/view :test/parking-root)]
                        :payload :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/" :request-method :get})
            drain    (future (with-open [^InputStream is (:body response)] (slurp is)))]
        (try
          (is (.await latch 5 java.util.concurrent.TimeUnit/SECONDS)
              "the writer thread reached the parking-section continuation")
          (reset! observed (rf.ssr.ring.test-support/live-streaming-threads))
          (finally
            (.countDown release)
            @drain))
        (is (seq @observed)
            "at least one rf2-ssr-streaming-* daemon thread was alive
             while the writer was mid-render")
        (let [names (map (fn [^Thread t] (.getName t)) @observed)]
          (is (every? #(.startsWith ^String % rf.ssr.ring.test-support/daemon-thread-name-prefix) names)
              (str "every captured thread name starts with the
                   `rf2-ssr-streaming-` prefix — captured names: "
                   (vec names))))
        ;; The writer is genuinely a daemon thread, not just
        ;; named one — a writer blocked on `.write` to a slow-loris
        ;; client's bounded pipe must NOT pin the JVM open at shutdown.
        ;; Captured live, this is the only place the daemon flag is
        ;; observable; assert it for real so a future regression that
        ;; drops `(.setDaemon true)` fails here.
        (is (every? (fn [^Thread t] (.isDaemon t)) @observed)
            "every live streaming writer thread is a daemon thread")
        ;; And of course: the daemon terminates after the request
        ;; completes.
        (let [leaked (await-no-streaming-threads! 5000)]
          (is (empty? leaked)
              "happy-path streaming also exits without leaking a
               daemon thread"))))))

;; ===========================================================================
;; Test 3 — head-materialisation throw must not orphan the pipe
;;          or leak a writer thread
;; ===========================================================================
;;
;; `ssr-response->ring-response` folds the response accumulator's
;; cookies/headers into the Ring head, and cookie/header serialisation
;; CAN throw at materialise time on a value that escaped the fx
;; boundary's PARTIAL validation. The fx-boundary `validate-cookie!`
;; gate is a CR/LF/NUL injection check (it str-coerces every attribute
;; and bans header-splitting chars), but `cookie->set-cookie-header`
;; carries an ADDITIONAL type contract the fx gate does not replicate:
;; `:expires` must be `integer?` (it is fed to `Instant/ofEpochMilli`
;; as a primitive long). So a cookie like
;; {:name "s" :value "v" :expires "not-an-int"} — a non-integer
;; :expires with no CR/LF — PASSES the fx gate (no injection char), is
;; stored on the accumulator, and THEN throws
;; `:rf.error/cookie-invalid-expires` when the host adapter materialises
;; the head. (A CR/LF-bearing :max-age is CRLF-gated at the fx boundary
;; too, so it never reaches head materialisation — the non-integer :expires
;; type-contract divergence is the genuine escape path.)
;;
;; Spawning + starting the daemon writer thread BEFORE materialising the
;; head would mean that when the head throw fires, the Ring response handed
;; back is the :on-error 500 — but the writer thread is already pumping the
;; FULL body into a pipe whose reader (`pipe-in`) is never returned to
;; anyone. With a body
;; larger than the 16 KiB pipe buffer the writer BLOCKS FOREVER on
;; `.write` (no consumer) — one live daemon thread leaked per such
;; request, a resource-exhaustion vector.
;;
;; So the head is materialised FIRST. A head-materialisation throw then
;; short-circuits to the outer catch BEFORE any pipe or thread exists —
;; the handler returns the :on-error 500 and NO `rf2-ssr-streaming-*`
;; thread is ever spawned.
;;
;; This test pins BOTH halves of the contract:
;;   - the handler returns a contained 500 (not a streamed body), and
;;   - no orphan `rf2-ssr-streaming-*` daemon thread is alive afterward.
;; The body is deliberately sized past the 16 KiB pipe buffer so that under
;; a writer-first ordering the orphaned writer would block forever (a
;; detectable leak), giving the test genuine discriminating power.

(deftest head-materialisation-throw-does-not-orphan-writer
  (testing "a cookie that throws at head materialisation
            (escaped the fx boundary) short-circuits to :on-error with
            NO writer thread spawned + NO orphaned pipe"
    ;; :initial-events sets a cookie whose :expires is a non-integer string.
    ;; The fx boundary (`validate-cookie!`) is a CR/LF/NUL injection gate
    ;; — it str-coerces every attribute and bans header-splitting chars,
    ;; but does NOT type-check :expires — so this cookie (no injection
    ;; char) passes the gate and is stored on the response accumulator.
    ;; The host materialiser's `cookie->set-cookie-header` carries the
    ;; primitive-long type contract (:expires → `Instant/ofEpochMilli`)
    ;; and throws `:rf.error/cookie-invalid-expires` at head materialise.
    (rf/reg-event :rf.test.server/init-bad-cookie
      {:platforms #{:server}}
      (fn [_ _]
        {:db {}
         :fx [[:rf.server/set-cookie {:name "s" :value "v" :expires "not-an-int"}]]}))
    ;; A root-view emitting a body well past the 16 KiB pipe buffer, so
    ;; that under a writer-first ordering the orphaned writer would
    ;; block forever on `.write` — making the leak deterministic. Under
    ;; the head-first ordering the writer never starts (the head throws
    ;; first, before the writer is spawned).
    (rf/reg-view ^{:rf/id :test/big-root} big-root []
      (into [:div]
            (for [i (range 4000)]
              ^{:key i} [:p (str "row-" i "-padding-padding-padding")])))
    (let [handler   (rf.ssr.ring/stream-handler
                      {:initial-events [[:rf.test.server/init-bad-cookie]]
                       :root-view [(rf/view :test/big-root)]
                       :payload :rf.ssr.payload/whole-app-db})
          ;; Direct handler call (no Jetty needed — the throw is on the
          ;; request thread during head materialisation, before any
          ;; transport hand-off).
          response  (handler {:uri "/" :request-method :get})]
      ;; The response is the contained :on-error 500 — NOT a streamed
      ;; body. The head throw short-circuited to the outer catch.
      (is (= 500 (:status response))
          "head-materialisation throw routed to the :on-error 500
           (locked default), not a partially-streamed body")
      (is (= "Internal error" (:body response))
          "the locked default-on-error String body, NOT a PipedInputStream —
           no streaming pipe was ever handed out, and no cookie internals
           reach the wire (the topology-leak contract)")
      ;; The load-bearing assertion: NO writer thread leaked. Under a
      ;; writer-first ordering the writer would be spawned before the head throw and,
      ;; with this oversized body, would block forever on a reader-less
      ;; pipe — `await-no-streaming-threads!` would time out non-empty.
      (let [leaked (await-no-streaming-threads! 5000)]
        (is (empty? leaked)
            (str "no orphan rf2-ssr-streaming-* daemon thread after a
                 head-materialisation throw — the writer must not be
                 spawned until the head is known materialisable.
                 Live threads observed: "
                 (mapv (fn [^Thread t] (.getName t)) leaked)))))))

;; ===========================================================================
;; Test 4 — a body nobody drains cannot pin the writer, the
;;          request frame or the request slot for ever
;; ===========================================================================
;;
;; Middleware that drops the streamed body without reading or closing it —
;; Ring core's own `wrap-head`, `(assoc response :body nil)` on every HEAD —
;; would leave a page larger than the 16 KiB pipe parked in the JDK's
;; `PipedInputStream.awaitSpace` for the life of the JVM: one writer thread,
;; one `:rf.frame/*` frame and one request slot per request. A live reader
;; that drains a few bytes and then abandons the body unclosed would leak the
;; same set. The writer hands the pipe only what fits and aborts through
;; its ordinary catch/finally once the consumer has drained nothing for
;; `streaming/stall-timeout-ms` (60 s; redefined to `stall-limit-ms` here).
;;
;; Counts are read in the test body, before the `:each` fixture's reset
;; clears the slots and would hide a leak. Every test closes the bodies it
;; captured in a `finally`, so a regression does not leak into later tests.

(def ^:private stall-limit-ms
  "What these tests redefine `streaming/stall-timeout-ms` to."
  500)

(defn- register-sized-page!
  "Register a no-op server init event and `:test/sized-root`: `rows`
  paragraphs of about 38 bytes each."
  [rows]
  (rf/reg-event :rf.test.server/init-sized
    {:platforms #{:server}}
    (fn [_ _] {:db {}}))
  (rf/reg-view* :test/sized-root
    (fn []
      (into [:div]
            (for [i (range rows)]
              ^{:key i} [:p (str "row-" i "-padding-padding-padding")])))))

(defn- recording-stream-handler
  "A `stream-handler` over `:test/sized-root` that also records each
  response body into `bodies`, so a test can close a body the middleware
  under test dropped."
  [bodies]
  (let [handler (rf.ssr.ring/stream-handler
                  {:initial-events [[:rf.test.server/init-sized]]
                   :root-view      (fn [] ((rf/view :test/sized-root)))
                   :payload        :rf.ssr.payload/whole-app-db})]
    (fn [request]
      (let [response (handler request)]
        (swap! bodies conj (:body response))
        response))))

(defn- close-bodies! [bodies]
  (doseq [body @bodies]
    (when (instance? InputStream body)
      (.close ^InputStream body))))

(defn- leak-census
  "Live `rf2-ssr-streaming-*` writer threads, live `:rf.frame/*` request
  frames, and filled request slots."
  []
  {:writers (count (rf.ssr.ring.test-support/live-streaming-threads))
   :frames  (count (rf.frame/frame-ids "rf.frame"))
   :slots   (count @rf.ssr.request/request-slots)})

(def ^:private no-leak {:writers 0 :frames 0 :slots 0})

(defn- await-no-leak!
  "Poll `leak-census` until it reads `no-leak` or `timeout-ms` elapses;
  return the last reading."
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [census (leak-census)]
        (if (or (= no-leak census) (>= (System/currentTimeMillis) deadline))
          census
          (do (Thread/sleep (long leak-poll-ms)) (recur)))))))

(defn- with-writer-failed-records
  "Call `f` with an always-on error listener attached; return
  `[(f) records]`, `records` being the `:rf.error/ssr-streaming-writer-failed`
  records emitted meanwhile."
  [f]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener!
      ::stall-recorder (fn [record] (swap! seen conj record)))
    (try
      (let [result (f)]
        [result (filterv #(= :rf.error/ssr-streaming-writer-failed (:error %))
                         @seen)])
      (finally
        (rf.error-emit/unregister-error-listener! ::stall-recorder)))))

(deftest wrap-head-dropped-body-is-reclaimed-within-the-stall-limit
  (testing "Ring's wrap-head drops a streamed body larger than
            the 16 KiB pipe, unread and unclosed; the writer thread, the
            request frame and the request slot are reclaimed within the stall
            limit, with one always-on writer-failed record"
    (register-sized-page! 1000)
    (let [bodies (atom [])
          app    (ring.head/wrap-head (recording-stream-handler bodies))]
      (try
        (with-redefs [rf.ssr.ring.streaming/stall-timeout-ms stall-limit-ms]
          (let [[[response census] records]
                (with-writer-failed-records
                  (fn []
                    (let [response (app {:request-method :head :uri "/"})]
                      [response (await-no-leak! 3000)])))]
            (is (= 200 (:status response)) "HEAD answers the GET's status")
            (is (nil? (:body response)) "wrap-head dropped the body")
            (is (instance? InputStream (first @bodies))
                "the handler did hand out a streamed body")
            (is (= no-leak census)
                "no writer thread, request frame or request slot outlives the
                 stall limit (without the limit: 1 / 1 / 1, for the life of the
                 JVM)")
            (is (= 1 (count records))
                "exactly one always-on writer-failed record — the visible
                 signal that a middleware discards bodies")
            (is (= "java.util.concurrent.TimeoutException"
                   (:ex-class (first records)))
                "the stall limit stopped the writer, not some other failure")))
        (finally (close-bodies! bodies))))))

(deftest steady-slow-reader-gets-the-whole-body-over-longer-than-the-limit
  (testing "control: the limit measures NO PROGRESS, not total
            time — a reader draining 4 KiB every 150 ms receives the complete
            body over well over the stall limit"
    (register-sized-page! 1000)
    (let [bodies  (atom [])
          handler (recording-stream-handler bodies)]
      (try
        (with-redefs [rf.ssr.ring.streaming/stall-timeout-ms stall-limit-ms]
          (let [[[html elapsed-ms] records]
                (with-writer-failed-records
                  (fn []
                    (let [^InputStream is (:body (handler {:request-method :get :uri "/"}))
                          ^java.io.ByteArrayOutputStream
                          out   (java.io.ByteArrayOutputStream.)
                          buf   (byte-array 4096)
                          start (System/currentTimeMillis)]
                      (loop []
                        (let [n (.read is buf 0 4096)]
                          (when (pos? n)
                            (.write out buf 0 n)
                            (Thread/sleep 150)
                            (recur))))
                      (.close is)
                      [(.toString out "UTF-8")
                       (- (System/currentTimeMillis) start)])))]
            (is (> elapsed-ms (* 2 stall-limit-ms))
                "the read took well over the stall limit in total")
            (is (str/includes? html "row-999-padding") "the last row arrived")
            (is (str/ends-with? html "</body></html>") "the document closed")
            (is (empty? records) "no writer-failed record")))
        (finally (close-bodies! bodies))))))

(deftest small-dropped-body-fits-the-pipe-and-ends-without-a-record
  (testing "control: a page that fits the 16 KiB pipe, dropped
            by wrap-head, ends at once — nothing waits and nothing is recorded"
    (register-sized-page! 10)
    (let [bodies (atom [])
          app    (ring.head/wrap-head (recording-stream-handler bodies))]
      (try
        (with-redefs [rf.ssr.ring.streaming/stall-timeout-ms stall-limit-ms]
          (let [[census records]
                (with-writer-failed-records
                  (fn []
                    (app {:request-method :head :uri "/"})
                    (await-no-leak! 3000)))]
            (is (= no-leak census))
            (is (empty? records) "no writer-failed record")))
        (finally (close-bodies! bodies))))))

;; ===========================================================================
;; A final payload the refusal stops mid-stream
;; ===========================================================================
;;
;; `ssr-handler` builds its payload before committing anything, so a payload
;; refusal there is an ordinary projected 500 (`ring_test`'s
;; `handler-payload-number-refusal-is-a-projected-500`). The streaming writer
;; builds the final payload AFTER the head and the shell are on the wire, so
;; the same refusal can only truncate.

(deftest payload-number-refusal-truncates-the-stream-and-reclaims-everything
  (testing "a final payload carrying a Long past 2^53 is refused
            with :rf.error/ssr-hydration-payload-invalid
            after the 200 was committed. The body stops before any
            __rf_payload, the writer reports exactly one
            :rf.error/ssr-streaming-writer-failed naming the refusal, and the
            writer thread, request frame and request slot are reclaimed"
    (rf/reg-event :rf.test.server/init-wide-order-id
      {:platforms #{:server}}
      (fn [_ _] {:db {:order {:id 9007199254740993}}}))
    (let [handler (rf.ssr.ring/stream-handler
                    {:initial-events [[:rf.test.server/init-wide-order-id]]
                     :root-view      [:div "page"]
                     :payload        [:order]})]
      (with-trace-recorder! [captured]
        (let [response (handler {:request-method :get :uri "/order"})
              html     (with-open [^InputStream is (:body response)]
                         (slurp is))
              census   (await-no-leak! 3000)
              failures (filterv #(= :rf.error/ssr-streaming-writer-failed (:operation %))
                                @captured)
              failure  (first failures)]
          (is (= 200 (:status response))
              "the head was committed before the final payload was built")
          (is (str/includes? html "page") "the shell was streamed")
          (is (not (str/includes? html "__rf_payload"))
              "the stream stops before any payload script")
          (is (not (str/includes? html "9007199254740993"))
              "the refused value is nowhere on the wire")
          (is (= 1 (count failures)) "exactly one writer-failed trace")
          (is (str/ends-with? (str (-> failure :tags :exception))
                              "[:rf.error/ssr-hydration-payload-invalid]")
              "it names the payload refusal")
          (is (= "clojure.lang.ExceptionInfo" (-> failure :tags :ex-class)))
          (is (= :truncate-and-close (:recovery failure)))
          (is (= no-leak census)
              "no writer thread, request frame or request slot outlives the
               truncated response"))))))
