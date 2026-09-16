(ns re-frame.http-abort-cancels-exchange-test
  "rf2-1eng8 seam 1, REFUTED and then pinned — on the JVM a lifecycle abort
  DOES cancel the exchange, and no code of ours makes that happen.

  ## What was claimed, and what is true

  rf2-1eng8 proposed that an abort cancelled only the DERIVED future:
  `jvm-fetch` returns `future-resp.thenApply(…)`, `run-attempt!` publishes that
  dependent stage to the abort closure, and the JDK does not generally
  propagate a dependent's cancellation to its source — so a superseded request
  was said to keep its connection open and keep downloading, one live
  connection per keystroke in a debounce search.

  Measured on JDK 21.0.10 against the unfixed tree, that does not happen. The
  JDK's `java.net.http` stack propagates a CANCEL from the dependent stage back
  to the exchange on its own. The tell is visible in the stage itself: it is a
  `jdk.internal.net.http.common.MinimalFuture`, and cancelling one stores a
  `CompletionException` WRAPPING the `CancellationException` rather than the
  bare exception a plain `CompletableFuture` stores — so `.isCancelled` reads
  FALSE on a stage `.cancel` just returned true for.

  ## The distinction the proposal missed: cancel vs completeExceptionally

  `jvm-fetch`'s timeout docstring is right that \"timing out the result alone
  would leave the download running\", and rf2-fzbj.11's explicit
  `.cancel future-resp true` on the timeout path IS load-bearing — because
  `orTimeout` completes the stage EXCEPTIONALLY, which the JDK does not
  propagate. A lifecycle abort calls `cancel()`, which it does. Measured, on a
  replica of `jvm-fetch`'s shape carrying no `whenComplete` at all:

      orTimeout ONLY (no upstream cancel):   [:wrote-all]
      orTimeout + explicit upstream cancel:  [:write-failed]

  So the two paths genuinely differ, the existing timeout code is correct, and
  extending it to aborts is what does not follow.

  ## Why this file exists at all

  The behaviour the transport relies on is a property of the JDK rather than of
  this repository, which is exactly the kind of thing that breaks silently
  under a runtime upgrade or a refactor of how the abort closure gets its
  handle. These tests characterise it so that break is caught here, with a
  message saying what changed, rather than as a connection leak in production.

  ## The harness, and why BOTH arms are in the test

  Headers and one body byte go out at once; the rest of the body waits on a
  latch the test opens only after it has its verdict. A write that FAILS after
  release is the server-side proof that the client tore the exchange down —
  and a write that SUCCEEDS is the proof that the harness can observe a
  survivor. Running only the cancelling arm would pass against a harness that
  reports `:write-failed` for some unrelated reason (a closed server, a stopped
  executor), so the non-cancelling arm runs first as the control, inside the
  same test. That control is what caught the refutation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io IOException]
           [java.net InetSocketAddress]
           [java.util.concurrent CompletableFuture CountDownLatch ExecutorService
                                 Executors TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private chunk-size 65536)

;; A body big enough that the post-release write loop is still running when a
;; cancelled connection drops under it. The same size the sibling body-timeout
;; test uses.
(def ^:private body-bytes (* 8 1024 1024))

(defn- start-stalled-body-server!
  "Serve a `body-bytes` body: headers and the first byte at once, the remainder
  only after `release` opens (bounded, so a failing run cannot pin a handler
  thread). `entries` counts exchanges that reached the stall; each exchange then
  conj's `:wrote-all` or `:write-failed` onto `outcomes`. A thread pool lets a
  second request's exchange run while the first is still parked on the latch."
  [^CountDownLatch release outcomes entries]
  (let [server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex]
            (try
              (.sendResponseHeaders ex 200 (long body-bytes))
              (let [os    (.getResponseBody ex)
                    chunk (byte-array chunk-size (byte 66))]
                (.write os (int 65))
                (.flush os)
                (swap! entries inc)
                (.await release 10 TimeUnit/SECONDS)
                (try
                  (loop [sent 1]
                    (when (< sent body-bytes)
                      (let [n (min chunk-size (- body-bytes sent))]
                        (.write os chunk 0 (int n))
                        (recur (+ sent n)))))
                  (.flush os)
                  (swap! outcomes conj :wrote-all)
                  (catch IOException _
                    (swap! outcomes conj :write-failed))))
              (catch Throwable _ nil)
              (finally (.close ex)))))))
    (.setExecutor server executor)
    (.start server)
    {:server   server
     :executor executor
     :url      (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/body")}))

(defn- stop-server! [{:keys [^HttpServer server ^ExecutorService executor]}]
  (.stop server 0)
  (.shutdownNow executor))

(defn- fetch-outcome
  "Issue one `jvm-fetch` against a stalled-body server, optionally cancel the
  returned stage while the exchange is LIVE, then release the body and return
  the server's verdict: `:wrote-all` (the download ran to completion) or
  `:write-failed` (the client tore the exchange down).

  Returns `{:outcome … :entries … :done-before-act? …}` so the caller can
  assert the PRECONDITION — that the exchange really was in flight when the
  act happened — rather than assume it."
  [timeout-ms cancel?]
  (let [release  (CountDownLatch. 1)
        outcomes (atom [])
        entries  (atom 0)
        srv      (start-stalled-body-server! release outcomes entries)]
    (try
      (let [^CompletableFuture cf (rf.http.transport-jvm/jvm-fetch
                                    {:method     :get
                                     :url        (:url srv)
                                     :decode     :text
                                     :timeout-ms timeout-ms})]
        (rf.test-support/poll-until #(= 1 @entries)
                                    {:timeout-ms 5000 :interval-ms 10
                                     :label "exchange stalled mid-body"})
        (let [done-before (.isDone cf)]
          (when cancel? (.cancel cf true))
          (.countDown release)
          (rf.test-support/poll-until #(= 1 (count @outcomes))
                                      {:timeout-ms 5000 :interval-ms 10
                                       :label "server-side outcome"})
          {:outcome          (first @outcomes)
           :entries          @entries
           :done-before-act? done-before}))
      (finally
        (.countDown release)
        (stop-server! srv)))))

;; ===========================================================================
;; The characterisation: a cancel reaches the exchange; nothing else does.
;; ===========================================================================

(deftest cancelling-the-returned-stage-tears-down-the-exchange
  (testing "rf2-1eng8 — cancelling the stage `jvm-fetch` returns (the one the
            lifecycle publishes to the abort closure) tears the UPSTREAM
            exchange down, for a request that opted out of `:timeout-ms` and
            one whose configured deadline has not fired. The JDK does this
            itself: no re-frame code arms it, which is why the seam this bead
            proposed does not exist"
    (doseq [timeout-ms [nil 5000]]
      ;; ---- CONTROL ARM, first ------------------------------------------
      ;; Without a cancel the download must run to completion. This is what
      ;; makes the verdict arm meaningful: it proves the harness can observe a
      ;; SURVIVING exchange, so `:write-failed` below is caused by the cancel
      ;; and not by an incidental teardown. Run the control before the verdict
      ;; so a harness that can only ever report `:write-failed` reds HERE.
      (let [{:keys [outcome entries done-before-act?]} (fetch-outcome timeout-ms false)]
        (is (= 1 entries)
            (str ":timeout-ms " (pr-str timeout-ms)
                 " — PRECONDITION (control): the exchange reached the wire and stalled mid-body"))
        (is (not done-before-act?)
            (str ":timeout-ms " (pr-str timeout-ms)
                 " — PRECONDITION (control): the request was still in flight"))
        (is (= :wrote-all outcome)
            (str ":timeout-ms " (pr-str timeout-ms)
                 " — CONTROL: an un-cancelled exchange drains in full, so the"
                 " harness can tell a survivor from a teardown")))

      ;; ---- VERDICT ARM --------------------------------------------------
      (let [{:keys [outcome entries done-before-act?]} (fetch-outcome timeout-ms true)]
        (is (= 1 entries)
            (str ":timeout-ms " (pr-str timeout-ms)
                 " — PRECONDITION: the exchange reached the wire and stalled mid-body"))
        (is (not done-before-act?)
            (str ":timeout-ms " (pr-str timeout-ms)
                 " — PRECONDITION: the request was still in flight when the cancel fired"
                 " (cancelling a completed request proves nothing about the exchange)"))
        (is (= :write-failed outcome)
            (str ":timeout-ms " (pr-str timeout-ms)
                 " — the cancel reached the EXCHANGE and closed the connection."
                 " `:wrote-all` here would mean the JDK stopped propagating"
                 " cancellation from a dependent stage, and the abort path"
                 " really would leak a live download per abort"))))))

(deftest superseded-request-stops-downloading
  (testing "rf2-1eng8 — the reported symptom, end to end: a `:request-id`
            supersede (the debounce-search shape) tears the superseded
            request's exchange down, so a keystroke does not leave a live
            connection behind"
    (let [release  (CountDownLatch. 1)
          outcomes (atom [])
          entries  (atom 0)
          srv      (start-stalled-body-server! release outcomes entries)]
      (try
        (rf/reg-event :debounce/reply (fn [_ _] {}))
        (rf/reg-event :debounce/search
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (:url srv)}
                    :decode     :text
                    ;; One logical request-id — the second dispatch supersedes
                    ;; the first, which is what a debounce search does on every
                    ;; keystroke.
                    :request-id :debounce/query
                    ;; Opt out of the deadline so the timeout path cannot be
                    ;; what closes the connection; the supersede must do it.
                    :timeout-ms nil
                    :reply-to   [:debounce/reply]}]]}))

        ;; Keystroke 1 — issue, and wait until its exchange is LIVE.
        (rf/dispatch-sync [:debounce/search])
        (rf.test-support/poll-until #(= 1 @entries)
                                    {:timeout-ms 5000 :interval-ms 10
                                     :label "first exchange stalled mid-body"})
        ;; ---- PRECONDITIONS ---------------------------------------------
        (is (= 1 @entries)
            "PRECONDITION: the first request reached the wire and stalled mid-body")
        (is (empty? @outcomes)
            "PRECONDITION: the first request was still downloading when it was superseded")

        ;; Keystroke 2 — supersedes the first.
        (rf/dispatch-sync [:debounce/search])
        (rf.test-support/poll-until #(= 2 @entries)
                                    {:timeout-ms 5000 :interval-ms 10
                                     :label "second exchange stalled mid-body"})
        (is (= 2 @entries)
            "PRECONDITION: the supersede happened while BOTH exchanges were live")

        ;; ---- VERDICT ----------------------------------------------------
        ;; The surviving second request is its own control here: exactly one of
        ;; the two exchanges must fail its write. Asserting the PAIR rather than
        ;; `(some #{:write-failed})` is what stops this passing if the harness
        ;; tore BOTH down for an unrelated reason.
        (.countDown release)
        (rf.test-support/poll-until #(= 2 (count @outcomes))
                                    {:timeout-ms 5000 :interval-ms 10
                                     :label "server-side outcomes"})
        (is (= {:write-failed 1 :wrote-all 1} (frequencies @outcomes))
            "exactly one exchange was torn down — the SUPERSEDED one — while the
             live successor drained in full")
        (finally
          (.countDown release)
          (stop-server! srv))))))
