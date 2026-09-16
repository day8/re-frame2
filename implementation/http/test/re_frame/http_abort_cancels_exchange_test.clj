(ns re-frame.http-abort-cancels-exchange-test
  "rf2-1eng8 seam 1 — on the JVM an ABORT must cancel the EXCHANGE, not merely
  the derived future.

  `jvm-fetch` returns `future-resp.thenApply(…)` — a DEPENDENT stage — and
  `run-attempt!` publishes exactly that stage to the abort closure's holder, so
  every lifecycle cancellation (user abort, `:request-id` supersede,
  actor-destroy) calls `.cancel` on it. The JDK does not propagate a dependent's
  cancellation back to its source, so `sendAsync`'s exchange kept running: the
  connection stayed open and the body kept downloading behind a request the app
  had already been told was cancelled. In a debounce search that is one live
  connection per keystroke.

  `jvm-fetch`'s own timeout docstring already draws this distinction — the
  `orTimeout` deadline cancels `future-resp` explicitly because \"timing out the
  result alone would leave the download running\" — but that callback fired only
  on a `TimeoutException`, and only when a positive `:timeout-ms` had been
  configured. Both gaps are what these tests pin.

  The server is the same shape as `re-frame.http-jvm-body-timeout-test`'s and
  for the same reason: headers and one body byte go out at once, the remainder
  waits on a latch the test opens only after it has its verdict. Once released
  the server tries to write the rest. **A write that FAILS is the server-side
  proof that the client cancelled the exchange**, rather than merely abandoning
  the result while the download ran on — which is precisely the distinction
  between the bug and the fix, and the only place it is observable.

  Each test asserts its own PRECONDITION before the verdict: that the exchange
  was LIVE and stalled mid-body when the cancel fired. A cancel of a request
  that had already completed proves nothing, and would pass either way."
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

;; ===========================================================================
;; The transport seam itself — cancelling the future `run-attempt!` publishes
;; to the abort closure must reach the exchange.
;; ===========================================================================

(deftest cancelling-the-returned-future-cancels-the-exchange
  (testing "rf2-1eng8 — cancelling the future `jvm-fetch` returns (the one the
            lifecycle publishes to the abort closure) cancels the UPSTREAM
            exchange, for BOTH a request that opted out of `:timeout-ms` and one
            whose configured deadline has not fired"
    ;; Two cases, one per half of the defect:
    ;;
    ;;   nil  — pre-fix the upstream-cancel callback was registered INSIDE
    ;;          `(when (pos? timeout-ms) …)`, so a request that opted out of the
    ;;          timeout armed nothing at all and a cancel reached nothing.
    ;;   5000 — pre-fix the callback WAS registered, but fired only on a
    ;;          `TimeoutException`; a `.cancel` completes the stage with a
    ;;          `CancellationException`, which it ignored.
    ;;
    ;; Post-fix both cancel `future-resp`, and the server's stalled write fails.
    (doseq [timeout-ms [nil 5000]]
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
            ;; ---- PRECONDITION ------------------------------------------
            ;; The exchange must be LIVE and stalled mid-body before we
            ;; cancel. Cancelling an already-completed request proves nothing
            ;; about the exchange and would pass against the unfixed tree.
            (rf.test-support/poll-until #(= 1 @entries)
                                        {:timeout-ms 5000 :interval-ms 10
                                         :label "exchange stalled mid-body"})
            (is (= 1 @entries)
                (str ":timeout-ms " (pr-str timeout-ms)
                     " — PRECONDITION: the exchange reached the wire and stalled mid-body"))
            (is (not (.isDone cf))
                (str ":timeout-ms " (pr-str timeout-ms)
                     " — PRECONDITION: the request was still in flight when the cancel fired"))
            (is (empty? @outcomes)
                (str ":timeout-ms " (pr-str timeout-ms)
                     " — PRECONDITION: the body was still being withheld"))

            ;; ---- the act -----------------------------------------------
            ;; This is exactly what the lifecycle abort closure does: it holds
            ;; the future `jvm-fetch` returned and cancels it.
            (is (.cancel cf true)
                (str ":timeout-ms " (pr-str timeout-ms) " — the cancel was applied"))
            ;; NOT `.isCancelled`, which reads FALSE here and would be a
            ;; wrong assertion rather than a strict one. `jvm-fetch`'s stage
            ;; is a `jdk.internal.net.http.common.MinimalFuture` (the JDK's
            ;; own CompletableFuture subclass, which `thenApply` propagates
            ;; from the `sendAsync` future), and cancelling one stores a
            ;; `CompletionException` WRAPPING the `CancellationException`
            ;; rather than the bare exception a plain CompletableFuture
            ;; stores. `isCancelled()` tests for the bare form, so it reads
            ;; false on a stage that `cancel()` just returned true for.
            ;; Measured on this tree: cancel=true, isCancelled=false,
            ;; isDone=true, isCompletedExceptionally=true, and the stored
            ;; throwable is CompletionException caused by
            ;; CancellationException. That wrapping is exactly why the fix
            ;; unwraps before testing — see `cancellation?` in
            ;; `transport_jvm.cljc`.
            (is (and (.isDone cf) (.isCompletedExceptionally cf))
                (str ":timeout-ms " (pr-str timeout-ms)
                     " — the derived stage completed exceptionally, not with a value"))

            ;; ---- the verdict -------------------------------------------
            ;; Release the withheld body. A cancelled exchange makes the
            ;; server's remaining writes fail; an abandoned-but-running one
            ;; drains happily, which is the pre-fix behaviour.
            (.countDown release)
            (rf.test-support/poll-until #(= 1 (count @outcomes))
                                        {:timeout-ms 5000 :interval-ms 10
                                         :label "server-side outcome"})
            (is (= [:write-failed] @outcomes)
                (str ":timeout-ms " (pr-str timeout-ms)
                     " — the cancel reached the EXCHANGE and closed the connection"
                     " (`:wrote-all` here means the download ran on behind the cancel)")))
          (finally
            (.countDown release)
            (stop-server! srv)))))))

;; ===========================================================================
;; The reported symptom, end to end — a debounce search's superseded request
;; must stop downloading.
;; ===========================================================================

(deftest superseded-request-stops-downloading
  (testing "rf2-1eng8 — a `:request-id` supersede (the debounce-search shape)
            cancels the superseded request's EXCHANGE, so a keystroke does not
            leave a live connection behind"
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
        ;; ---- PRECONDITION ----------------------------------------------
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

        ;; ---- the verdict -----------------------------------------------
        (.countDown release)
        (rf.test-support/poll-until #(= 2 (count @outcomes))
                                    {:timeout-ms 5000 :interval-ms 10
                                     :label "server-side outcomes"})
        (is (contains? (set @outcomes) :write-failed)
            "the SUPERSEDED request's exchange was cancelled — pre-fix both
             exchanges drained in full, one live connection per keystroke")
        (finally
          (.countDown release)
          (stop-server! srv))))))
