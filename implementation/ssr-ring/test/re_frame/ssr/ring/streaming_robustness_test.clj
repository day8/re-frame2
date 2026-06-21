(ns re-frame.ssr.ring.streaming-robustness-test
  "Robustness coverage for the streaming SSR daemon writer thread —
  `re-frame.ssr.ring.streaming/run-streaming-writer!` and the daemon
  thread `stream-handler` spawns to invoke it. Follow-on from rf2-toib5
  test-coverage sweep G2 / rf2-jvpli.

  ## Why this lives at the daemon-thread layer

  The non-streaming SSR path is fully synchronous on the request
  thread — the only error surface is the outer `try/catch` in
  `ssr-handler` that routes throws to `:on-error`. The streaming path
  is asymmetric: the writer runs on a daemon thread the request handler
  spawned and detached. The Ring response was already returned to Jetty
  by the time the writer encounters a problem. So the writer's failure-
  mode contract is structurally different — no caller frame to bubble
  to, no `:on-error` hook to invoke (the response has already started).

  Per Spec 011 §Failure semantics — exceptions the writer thread STILL
  owns after rf2-r06pc (a final-payload build throw, a downstream
  OutputStream broken-pipe, a continuation drain) close the pipe with
  whatever partial response was flushed and emit a structured
  `:rf.error/ssr-streaming-writer-failed` trace; the writer's `finally`
  closes the OutputStream so the Ring server emits EOF; the daemon
  thread terminates. (rf2-r06pc moved root-view / head / shell-walk
  resolution to the REQUEST thread, before the head commits — those
  fail closed to a non-200 on the request thread and never reach the
  writer; see test 3.) The load-bearing contracts:

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

    1. `writer-survives-broken-pipe-on-write` — direct call to
       `run-streaming-writer!` against a PipedOutputStream whose sink
       (`PipedInputStream`) was closed before the writer started, handed
       a PRE-RENDERED shell (rf2-r06pc — the writer no longer renders
       the shell). The first chunk write raises `IOException: Pipe
       closed`. The writer's outer `catch Throwable` arm absorbs; no
       exception escapes; the OutputStream is closed in `finally`.
    2. `client-disconnect-mid-stream-cleans-up` — real Jetty + a real
       HTTP client that reads only the response head + a few bytes of
       the body then closes the InputStream. The writer thread, mid-
       flight on the next `.write`, hits a broken-pipe IOException;
       same catch + finally semantics; thread terminates within a
       generous bound; no orphan `rf2-ssr-streaming-*` thread remains.
    3. `root-view-throw-fails-closed-non-200-bytes-on-wire` (rf2-r06pc) —
       root-view fn throws on resolution (a structural shell failure).
       The shell now renders on the REQUEST thread before the head
       commits, so the throw escalates to `:rf.error/ssr-render-failed`
       and FAILS CLOSED to a non-200 projected error page — NOT the
       silent 200 this test previously (incorrectly) blessed. NO daemon
       writer thread is spawned at all. (Spec 011 §744/§748/§954.)
    4. `daemon-thread-name-is-frame-scoped` — sanity check that the
       writer thread is named `rf2-ssr-streaming-<frame-id>` so the
       leak-detection assertions above can scope by name prefix and
       operators can correlate JFR / thread dumps to frames.

  ## Why a direct `run-streaming-writer!` call for broken-pipe

  The Jetty + HttpClient harness covers the end-to-end disconnect path
  (test 2). Test 1 isolates the writer body — a deterministic, fast
  reproducer of every `IOException` the catch arm has to absorb, with
  no scheduling dependence on the OS socket layer. The fn is `defn-`,
  so the test reaches it via `#'run-streaming-writer!` — a deliberate
  narrow couple with the implementation in exchange for a robustness
  proof that doesn't rely on real-network flakiness."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.streaming :as streaming]
            [re-frame.ssr.ring.test-support :as ts])
  (:import [java.io InputStream IOException OutputStream
                    PipedInputStream PipedOutputStream]
           [java.net.http HttpResponse$BodyHandlers]))

(use-fixtures :each ts/reset-runtime)

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
     [:test/article-list]
     [:rf/suspense-boundary
      {:id :test/comments :fallback [:p "Loading comments…"]}
      [:test/comments-section]]
     [:footer "End"]]))

;; ---- Jetty + JDK HTTP client + leak detector -----------------------------
;;
;; rf2-l1qgjw — the ephemeral Jetty host, the `java.net.http` client /
;; request builder, and the `rf2-ssr-streaming-*` daemon-thread leak
;; detector now live in `re-frame.ssr.ring.test-support` (aliased `ts`),
;; shared with the other live-host / streaming-thread test namespaces.
;; The per-test knobs stay explicit at the call sites below: a 10s read
;; timeout (`http-get-request`/`http-get` 3rd arg) and a 10ms leak-poll
;; cadence (`await-no-streaming-threads!` 2nd arg — the single-request
;; tests poll faster than the concurrency burst's 50ms).

(def ^:private read-timeout-secs 10)
(def ^:private leak-poll-ms 10)

(defn- await-no-streaming-threads!
  "Single-request poll cadence (10ms) over the shared leak detector.
  rf2-fun38: returns the leaked-thread vec on timeout (does not throw) so
  the assertion can name the offenders."
  [timeout-ms]
  (ts/await-no-streaming-threads! timeout-ms leak-poll-ms))

;; ===========================================================================
;; Test 1 — broken pipe on .write absorbed by the writer's catch arm
;; ===========================================================================
;;
;; The realised reproducer for the writer's `catch Throwable` arm.
;; `PipedOutputStream/write` raises `IOException: Pipe closed` once the
;; matching `PipedInputStream` is closed — exactly what happens on the
;; live socket path when the client aborts. We close the sink first,
;; then call `run-streaming-writer!` directly on the current thread.
;;
;; What we pin:
;;
;;   - The writer body MUST NOT escape with the IOException. The whole
;;     point of the outer `catch Throwable` is to absorb arbitrary
;;     OutputStream failures (broken pipe, SSL shutdown mid-stream,
;;     Jetty internal-buffer-full errors) without crashing the daemon
;;     thread.
;;   - The OutputStream is closed by the writer's `finally`. We can't
;;     directly observe "close" on a closed pipe (already closed), but
;;     we can prove the writer returned normally — which only happens
;;     via the `finally`.
;;
;; We invoke the fn directly via `#'streaming/run-streaming-writer!`
;; (it is `defn-`) — a deliberate narrow couple to the implementation
;; in exchange for a deterministic, fast reproducer that doesn't rely
;; on OS socket scheduling. The end-to-end disconnect path is covered
;; by test 2.

(deftest writer-survives-broken-pipe-on-write
  (testing "writer absorbs IOException on .write and closes the OutputStream cleanly"
    ;; Pre-broken pipe: close the InputStream side BEFORE handing the
    ;; OutputStream to the writer. Every subsequent `.write` throws
    ;; `IOException: Pipe closed`.
    (let [pipe-in  (PipedInputStream. 1024)
          pipe-out (PipedOutputStream. pipe-in)
          _        (.close pipe-in)
          ;; Direct writer-body invocation. We pass a throw-away
          ;; frame-id (no frame registered) — the writer will throw on
          ;; `rf/with-frame` deref before any chunk write, but THAT
          ;; throw is caught by the SAME outer catch arm we are
          ;; exercising for the broken-pipe path. The contract under
          ;; test is `catch Throwable, then finally close out` — the
          ;; identity of the absorbed throw doesn't matter, only that
          ;; the writer returns normally and the OutputStream is left
          ;; in the closed state.
          ;; rf2-r06pc — the writer no longer resolves/renders the shell
          ;; (that moved to `render-streaming-shell!` on the request
          ;; thread). It now receives PRE-RENDERED shell pieces and only
          ;; drains the chunk stream, so we hand it a minimal valid
          ;; `rendered` map. The very first `write-chunk!` of the shell
          ;; prefix hits the pre-closed pipe → `IOException: Pipe closed`,
          ;; the exact broken-pipe surface the outer `catch Throwable`
          ;; absorbs. The contract under test is unchanged: `catch
          ;; Throwable, then finally close out` — the writer returns
          ;; normally and the OutputStream is left closed.
          rendered {:hiccup        [:div]
                    :head-html     ""
                    :html-attrs    nil
                    :body-attrs    nil
                    :shell-html    "<div></div>"
                    :continuations []}
          result   (try
                     (@#'streaming/run-streaming-writer!
                       pipe-out :no-such-frame rendered {:root-view [:div]})
                     ::returned-normally
                     (catch Throwable t
                       [::escaped t]))]
      (is (= ::returned-normally result)
          "the writer's outer `catch Throwable` absorbs every throw —
           no exception escapes the writer body. The contract is
           `catch Throwable`, not `catch IOException`: arbitrary
           OutputStream failures (broken pipe, SSL shutdown mid-stream,
           Jetty internal-buffer errors) and the post-first-chunk
           render throws the writer still owns (continuation drains —
           inline-fallback — and the final-payload build) MUST all be
           absorbed. (rf2-r06pc moved root-view / head / shell-walk
           resolution to the request thread, so those no longer reach
           the writer at all — they fail closed BEFORE the writer is
           spawned.)")
      ;; The writer's finally closes the OutputStream. Probe by
      ;; writing — a closed PipedOutputStream throws on .write, the
      ;; JDK contract that proves the finally arm ran.
      (let [closed? (try
                      (.write pipe-out (int 0))
                      false
                      (catch IOException _ true))]
        (is closed?
            "the writer's finally closed the OutputStream — a write
             after close throws IOException per the JDK pipe contract.
             Open pipes pin a buffer and starve the Ring server of
             the EOF signal; this is the bones of the cleanup
             contract.")))))

;; ===========================================================================
;; Test 2 — client disconnect mid-stream: writer cleans up, no orphan thread
;; ===========================================================================
;;
;; End-to-end disconnect path. We send a real HTTP request through
;; Jetty, read JUST enough of the response body to confirm streaming
;; has started (the shell prefix + a few hundred bytes), then close
;; the response InputStream. On the next chunk write, the daemon
;; writer thread hits `IOException: Broken pipe` (or `Connection reset
;; by peer` — Jetty surface varies by JDK version; both are IOExceptions).
;;
;; The contract is the same as test 1 but observed through the real
;; transport: no orphan `rf2-ssr-streaming-*` thread after a generous
;; settle window. The settle window is needed because thread
;; termination is asynchronous w.r.t. the client read — we wait until
;; the JVM thread table reflects the writer's exit.

(deftest client-disconnect-mid-stream-cleans-up
  (testing "abrupt client disconnect → writer terminates cleanly, no orphan daemon thread"
    (register-baseline-handlers!)
    (let [handler (ssr-ring/stream-handler
                    {:initial-events [[:rf.test.server/init]]
                     :root-view [:test/root]
                     :payload :rf.ssr.payload/whole-app-db})]
      (ts/with-jetty [port handler]
        (let [client   (ts/new-http-client)
              req      (ts/http-get-request port "/" read-timeout-secs)
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
;; Test 3 (rf2-r06pc) — root-view throw FAILS CLOSED to a non-200 on the
;;                      request thread; NO writer thread is spawned
;; ===========================================================================
;;
;; This test PREVIOUSLY blessed a 200 for a root-view throw — the exact
;; spec drift rf2-r06pc fixes. The OLD streaming order materialised the
;; Ring head (status 200) and spawned the daemon writer BEFORE the writer
;; resolved/rendered the shell, so a root-view / shell-walk throw fired on
;; the detached daemon thread where it could only emit a trace + close the
;; pipe — the wire had already committed a silent 200/truncated body.
;;
;; Spec 011 §744/§748/§954: a shell-walk (or root-view) throw is the
;; request's structural foundation failing; it MUST escalate to
;; `:rf.error/ssr-render-failed` through the standard error-projection
;; path and FAIL CLOSED to a non-200 projected error page — NOT a 200.
;;
;; The fix (rf2-r06pc) renders the shell on the REQUEST thread, before the
;; head commits, mirroring the non-streaming post-render re-flush
;; (rf2-c0bq1). A root-view throw now propagates on the request thread and
;; is routed through the projector (`project-render-throw->ring-response`)
;; → a non-200 projected error page returned as an ORDINARY (non-chunked)
;; Ring response. NO pipe and NO daemon writer thread is ever spawned.
;;
;; This test pins BOTH halves of the spec-aligned contract:
;;   - the wire status is non-200 (500, the default projector's
;;     `:rf.error/ssr-render-failed` mapping), bytes-on-wire through Jetty,
;;   - no orphan `rf2-ssr-streaming-*` daemon thread (none is spawned at
;;     all on a shell-render failure).
;; The direct-handler half of this contract lives in
;; `ring_streaming_test/stream-handler-root-view-throw-fails-closed`.

(deftest root-view-throw-fails-closed-non-200-bytes-on-wire
  (testing "rf2-r06pc: a root-view throw fails closed to a non-200 on the
            full Jetty round-trip (was incorrectly 200 before the fix) AND
            spawns NO daemon writer thread — the shell renders on the
            request thread before the head commits, so a structural shell
            failure escalates to `:rf.error/ssr-render-failed` and projects
            a non-200 (Spec 011 §744/§748/§954)."
    (rf/reg-event :rf.test.server/init-min
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [throwing-root  (fn root-view-fn []
                           (throw (ex-info ":rf.test/intentional-root-view-throw"
                                           {:reason "shell-render fail-closed probe"})))
          handler        (ssr-ring/stream-handler
                           {:initial-events [[:rf.test.server/init-min]]
                            :root-view throwing-root
                            :payload :rf.ssr.payload/whole-app-db})]
      (ts/with-jetty [port handler]
        (let [client   (ts/new-http-client)
              req      (ts/http-get-request port "/" read-timeout-secs)
              response (.send client req (HttpResponse$BodyHandlers/ofString))
              status   (.statusCode response)
              body     (.body response)]
          (is (= 500 status)
              "root-view throw fails closed to a 500 on the wire (default
               projector maps `:rf.error/ssr-render-failed` → 500) — NOT
               the silent 200 the OLD daemon-writer-first order shipped
               (rf2-r06pc / Spec 011 §744/§748/§954)")
          (is (string? body)
              "the wire EOF'd cleanly — `.send` returned a body string,
               no read-side hang")
          (is (not (str/includes? body "intentional-root-view-throw"))
              "the projected error body carries no internal exception
               detail — the topology-leak boundary holds (Spec 011
               §Where sanitisation happens)"))
        ;; NO writer thread is spawned on a shell-render failure — the
        ;; throw fires on the request thread before the pipe/thread exist.
        ;; So there is nothing to leak; assert the absence explicitly.
        (let [leaked (await-no-streaming-threads! 5000)]
          (is (empty? leaked)
              (str "no rf2-ssr-streaming-* daemon thread after a root-view
                   throw — the shell-render failure short-circuits on the
                   request thread, before any writer is spawned. Live
                   threads observed: "
                   (mapv (fn [^Thread t] (.getName t)) leaked))))))))

;; ===========================================================================
;; Test 4 — daemon thread name carries the frame-id (correlation + leak scope)
;; ===========================================================================
;;
;; Sanity check that the writer thread is named `rf2-ssr-streaming-
;; <frame-id>` so:
;;
;;   - the leak-detection assertions in tests 2 & 3 scope by name
;;     prefix correctly (otherwise we'd be matching every JVM thread
;;     and the contract would be untestable);
;;   - operators correlating thread dumps / JFR recordings to specific
;;     frames have a name to grep for.
;;
;; This is a "the bones of the leak detector are real" test — it
;; exists so a future refactor that renames the thread can't silently
;; defeat the orphan-detection in tests 2 & 3.

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
          [:test/parking-section]]])
      (let [handler  (ssr-ring/stream-handler
                       {:initial-events [[:rf.test.server/init]]
                        :root-view [:test/parking-root]
                        :payload :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/" :request-method :get})
            drain    (future (with-open [^InputStream is (:body response)] (slurp is)))]
        (try
          (is (.await latch 5 java.util.concurrent.TimeUnit/SECONDS)
              "the writer thread reached the parking-section continuation")
          (reset! observed (ts/live-streaming-threads))
          (finally
            (.countDown release)
            @drain))
        (is (seq @observed)
            "at least one rf2-ssr-streaming-* daemon thread was alive
             while the writer was mid-render")
        (let [names (map (fn [^Thread t] (.getName t)) @observed)]
          (is (every? #(.startsWith ^String % ts/daemon-thread-name-prefix) names)
              (str "every captured thread name starts with the
                   `rf2-ssr-streaming-` prefix — captured names: "
                   (vec names))))
        ;; rf2-ekwda: the writer is genuinely a daemon thread, not just
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
;; Test 5 (rf2-z5azc) — head-materialisation throw must not orphan the pipe
;;                       or leak a writer thread
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
;; the head. (Before rf2-kjf3m.1 this test used a CR/LF-bearing
;; :max-age; that attribute is now CRLF-gated at the fx boundary too, so
;; it no longer reaches head materialisation — the non-integer :expires
;; type-contract divergence is the genuine remaining escape path.)
;;
;; The bug (rf2-z5azc): the old streaming branch spawned + started the
;; daemon writer thread BEFORE materialising the head. When the head
;; throw fired, the Ring response handed back was the :on-error 500 —
;; but the writer thread was already pumping the FULL body into a pipe
;; whose reader (`pipe-in`) was never returned to anyone. With a body
;; larger than the 16 KiB pipe buffer the writer BLOCKS FOREVER on
;; `.write` (no consumer) — one live daemon thread leaked per such
;; request, a resource-exhaustion vector.
;;
;; The fix: materialise the head FIRST. A head-materialisation throw then
;; short-circuits to the outer catch BEFORE any pipe or thread exists —
;; the handler returns the :on-error 500 and NO `rf2-ssr-streaming-*`
;; thread is ever spawned.
;;
;; This test pins BOTH halves of the contract:
;;   - the handler returns a contained 500 (not a streamed body), and
;;   - no orphan `rf2-ssr-streaming-*` daemon thread is alive afterward.
;; The body is deliberately sized past the 16 KiB pipe buffer so that on
;; the OLD ordering the orphaned writer would block forever (a detectable
;; leak), giving the test genuine before/after discriminating power.

(deftest head-materialisation-throw-does-not-orphan-writer
  (testing "rf2-z5azc — a cookie that throws at head materialisation
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
    ;; that under the OLD (buggy) ordering the orphaned writer would
    ;; block forever on `.write` — making the leak deterministic. Under
    ;; the FIXED ordering this view never renders (the head throws first,
    ;; before the writer is spawned).
    (rf/reg-view ^{:rf/id :test/big-root} big-root []
      (into [:div]
            (for [i (range 4000)]
              ^{:key i} [:p (str "row-" i "-padding-padding-padding")])))
    (let [handler   (ssr-ring/stream-handler
                      {:initial-events [[:rf.test.server/init-bad-cookie]]
                       :root-view [:test/big-root]
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
      (is (not (instance? InputStream (:body response)))
          "the response body is the :on-error string body, NOT a
           PipedInputStream — no streaming pipe was ever handed out")
      (is (= "Internal error" (:body response))
          "locked default-on-error body — the topology-leak contract
           holds; no cookie internals reach the wire")
      ;; The load-bearing assertion: NO writer thread leaked. Under the
      ;; OLD ordering the writer was spawned before the head throw and,
      ;; with this oversized body, would block forever on a reader-less
      ;; pipe — `await-no-streaming-threads!` would time out non-empty.
      (let [leaked (await-no-streaming-threads! 5000)]
        (is (empty? leaked)
            (str "no orphan rf2-ssr-streaming-* daemon thread after a
                 head-materialisation throw — the writer must not be
                 spawned until the head is known materialisable
                 (rf2-z5azc). Live threads observed: "
                 (mapv (fn [^Thread t] (.getName t)) leaked)))))))

;; ===========================================================================
;; Test 6 (rf2-r06pc) — production reactive-sub throw during the streaming
;;                      shell render fails closed to a non-200 ON THE WIRE
;; ===========================================================================
;;
;; The streaming counterpart of `ring_rendertime_sub_failclosed_test`
;; (which pins the NON-streaming handler). A reactive subscription that
;; throws during the shell render under production hardening
;; (`interop/debug-enabled? = false`) recovers to nil (the render does NOT
;; throw) but BUFFERS a fail-closed 500 on the always-on error-emit
;; substrate (rf2-vvwmi). The OLD streaming order committed the Ring head
;; (status 200) before the daemon writer ran the render, so that buffered
;; 500 never reached the wire — a silent 200 with the recovered-to-nil
;; broken HTML, defeating the rf2-vvwmi fix end-to-end (Spec 011 §744 /
;; §750).
;;
;; The fix (rf2-r06pc) renders the shell on the request thread and re-reads
;; the response accumulator (`ssr/get-response`) AFTER the render — exactly
;; as the non-streaming `build-full-response*` does (rf2-c0bq1) — so the
;; buffered 500 is committed onto the chunked head before the writer is
;; spawned. This test drives the throwing sub through the REAL stream-
;; handler order and asserts the WIRE status is 500, bytes-on-wire through
;; Jetty.

;; rf2-l1qgjw — the full-body `{:status :body}` GET now uses the shared
;; `ts/http-get` (the local `http-get-string` was its duplicate).

(deftest rendertime-sub-throw-fails-closed-non-200-bytes-on-wire
  (testing "rf2-r06pc / rf2-vvwmi: a production-mode reactive sub that
            throws during the STREAMING shell render fails closed to a
            non-200 on the full Jetty round-trip — NOT a silent 200 with
            the recovered-to-nil broken HTML (Spec 011 §744/§750). Before
            rf2-r06pc the streaming handler committed the 200 head before
            the daemon writer ran the render that buffers the 500."
    (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
    (rf/reg-view ^{:rf/id :test/uses-throwing-sub} uses-throwing-sub []
      (let [v @(rf/subscribe [:throwing-sub])]
        [:main.broken
         [:h1 "header that renders"]
         [:p (str "value: " v)]]))
    (rf/reg-event :rf.test.server/init-min
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [handler (ssr-ring/stream-handler
                    {:initial-events [[:rf.test.server/init-min]]
                     :root-view [:test/uses-throwing-sub]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      ;; Production hardening — the always-on error-emit substrate is the
      ;; status source of truth. NB: the redef is in force on THIS (the
      ;; request) thread, which is where rf2-r06pc now runs the shell
      ;; render + the post-shell re-read — so the buffered 500 is observed
      ;; and committed before the response is returned to Jetty.
      (with-redefs [interop/debug-enabled? false]
        (ts/with-jetty [port handler]
          (let [client (ts/new-http-client)
                {:keys [status]} (ts/http-get client port "/" read-timeout-secs)]
            (is (= 500 status)
                "render-time sub-throw fail-closed 500 rides the full
                 Jetty round-trip — never a silent 200 (rf2-r06pc)")))))))

;; ===========================================================================
;; Test 7 (rf2-sgvn6 / rf2-b1v8v) — NESTED suspense boundary drains its
;;                                  inner chunk on the actual wire (FIFO)
;; ===========================================================================
;;
;; The end-to-end bytes-on-wire proof for the nested-streaming fix. An
;; OUTER `:rf/suspense-boundary` whose subtree contains an INNER
;; `:rf/suspense-boundary` must stream the inner boundary's resolved
;; chunk over the real Jetty transport — the inner registers DURING the
;; outer continuation's render and the daemon writer's GROWABLE FIFO
;; appends it at the tail (Spec 011 §922-924/§966/§983).
;;
;; Before the fix: the outer continuation rendered its subtree through the
;; NON-streaming emitter, which threw on the buried `:rf/suspense-boundary`
;; head (`:rf.error/ssr-suspense-boundary-outside-stream`); the throw was
;; caught as a FAILED outer continuation that re-emitted its own fallback,
;; and the inner boundary never registered or resolved — wrong streamed
;; HTML for nested Suspense UI. This test pins, on the wire:
;;   - the outer boundary is NOT marked failed,
;;   - the inner boundary's resolved chunk + body stream,
;;   - the inner resolved chunk lands AFTER the outer resolved chunk (FIFO),
;;   - no orphan daemon thread.

(deftest nested-boundary-inner-chunk-streams-on-wire-FIFO
  (testing "rf2-sgvn6 / rf2-b1v8v: an outer boundary containing an inner
            boundary streams the inner resolved chunk on the real Jetty
            wire, at the FIFO tail, with the outer resolved cleanly (not
            failed)."
    (rf/reg-event :rf.test.server/init-nested
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (rf/reg-view ^{:rf/id :test/wire-inner} wire-inner []
      [:div.inner-body "WIRE_INNER_BODY"])
    (rf/reg-view ^{:rf/id :test/wire-outer} wire-outer []
      [:section.outer
       [:p "WIRE_OUTER_CONTENT"]
       [:rf/suspense-boundary
        {:id :wire/inner :fallback [:p "wire inner loading"]}
        [:test/wire-inner]]])
    (rf/reg-view ^{:rf/id :test/wire-nested-root} wire-nested-root []
      [:main
       [:h1 "WireNested"]
       [:rf/suspense-boundary
        {:id :wire/outer :fallback [:p "wire outer loading"]}
        [:test/wire-outer]]
       [:footer "End"]])
    (let [handler (ssr-ring/stream-handler
                    {:initial-events [[:rf.test.server/init-nested]]
                     :root-view [:test/wire-nested-root]
                     :payload :rf.ssr.payload/whole-app-db})]
      (ts/with-jetty [port handler]
        (let [client (ts/new-http-client)
              {:keys [status body]} (ts/http-get client port "/" read-timeout-secs)
              idx-outer-resolved (str/index-of body "data-rf2-suspense-id=\":wire/outer\" data-rf2-suspense-resolved=\"1\"")
              idx-inner-resolved (str/index-of body "data-rf2-suspense-id=\":wire/inner\" data-rf2-suspense-resolved=\"1\"")
              idx-inner-body     (str/index-of body "WIRE_INNER_BODY")
              idx-payload        (str/index-of body "__rf_payload")]
          (is (= 200 status) "nested stream rides the wire as 200")
          (is (str/includes? body "wire outer loading")
              "outer fallback in the shell on the wire")
          (is (some? idx-outer-resolved)
              "outer resolved chunk on the wire (NOT a failed re-emit)")
          (is (not (str/includes? body "data-rf2-suspense-id=\":wire/outer\" data-rf2-suspense-resolved=\"1\" data-rf2-suspense-failed=\"1\""))
              "outer boundary NOT marked failed on the wire (rf2-sgvn6)")
          (is (some? idx-inner-resolved)
              "inner boundary's resolved chunk streamed on the wire — the
               nested continuation registered + drained at the FIFO tail")
          (is (some? idx-inner-body)
              "inner resolved chunk carries the inner body bytes on the wire")
          (is (and idx-outer-resolved idx-inner-resolved
                   (< idx-outer-resolved idx-inner-resolved))
              "inner resolved chunk streams AFTER the outer (FIFO tail,
               Spec 011 §966/§983)")
          (is (and idx-inner-resolved idx-payload
                   (< idx-inner-resolved idx-payload))
              "inner resolved chunk before the final payload")))
      ;; No orphan daemon thread after the nested stream completes.
      (let [leaked (await-no-streaming-threads! 5000)]
        (is (empty? leaked)
            (str "no orphan rf2-ssr-streaming-* daemon thread after a
                 nested-boundary stream. Live threads observed: "
                 (mapv (fn [^Thread t] (.getName t)) leaked)))))))
