(ns re-frame.http-abort-cancels-exchange-test
  "On the JVM a lifecycle abort cancels the exchange, and the JDK does it:
  `jvm-fetch` returns a dependent `thenApply` stage, the abort closure cancels
  that stage, and `java.net.http` propagates a CANCEL back to the exchange (an
  exceptional completion such as `orTimeout`'s is not propagated, which is why
  the timeout path cancels upstream explicitly). Without that propagation a
  superseded request would keep downloading, one live connection per keystroke
  in a debounce search. This is a property of the JDK, so it is characterised
  here, where a runtime upgrade that changes it goes red.

  The server sends headers and one body byte, then stalls the rest on a latch
  the test opens after it has acted. A failed write after release proves the
  client tore the exchange down; the un-cancelled control arm proves the
  harness can observe a survivor."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
  ;; Each arm first proves the exchange stalled mid-body and was still in
  ;; flight when it acted; the control runs first, so a harness that can only
  ;; report :write-failed goes red there.
  (doseq [timeout-ms [nil 5000]]
    (is (= [1 false :wrote-all]
           ((juxt :entries :done-before-act? :outcome) (fetch-outcome timeout-ms false)))
        (str ":timeout-ms " (pr-str timeout-ms) " control: an un-cancelled exchange drains in full"))
    (is (= [1 false :write-failed]
           ((juxt :entries :done-before-act? :outcome) (fetch-outcome timeout-ms true)))
        (str ":timeout-ms " (pr-str timeout-ms) ": the cancel reached the exchange and closed it"))))

(deftest superseded-request-stops-downloading
  ;; The debounce-search shape: each keystroke supersedes the last request.
  ;; :timeout-ms nil, so the supersede and not a deadline closes the connection.
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
                  :request-id :debounce/query
                  :timeout-ms nil
                  :reply-to   [:debounce/reply]}]]}))
      (rf/dispatch-sync [:debounce/search])
      (rf.test-support/poll-until #(= 1 @entries)
                                  {:timeout-ms 5000 :interval-ms 10
                                   :label "first exchange stalled mid-body"})
      (is (empty? @outcomes)
          "PRECONDITION: the first request was still downloading when it was superseded")
      (rf/dispatch-sync [:debounce/search])
      (rf.test-support/poll-until #(= 2 @entries)
                                  {:timeout-ms 5000 :interval-ms 10
                                   :label "second exchange stalled mid-body"})
      (.countDown release)
      (rf.test-support/poll-until #(= 2 (count @outcomes))
                                  {:timeout-ms 5000 :interval-ms 10
                                   :label "server-side outcomes"})
      ;; The live successor is the control: exactly one of the pair fails.
      (is (= {:write-failed 1 :wrote-all 1} (frequencies @outcomes)))
      (finally
        (.countDown release)
        (stop-server! srv)))))
