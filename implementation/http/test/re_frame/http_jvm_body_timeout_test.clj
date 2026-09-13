(ns re-frame.http-jvm-body-timeout-test
  "rf2-fzbj.11 / rf2-gwye.13 — on the JVM, `:timeout-ms` bounds the WHOLE
  attempt, response body included.

  `HttpRequest.Builder.timeout` stops protecting an attempt once the response
  HEADERS arrive, so an upstream that sends headers promptly and then stalls the
  body used to hold the request past its budget indefinitely — or deliver
  success after it. Spec 014 §`:timeout-ms` security defaults names exactly that
  slow-loris body as what the default exists to bound.

  The server is local and deterministic: headers and one body byte go out at
  once, and the rest of the body waits on a latch the test opens only after it
  has its verdict. Once released the server tries to write the remaining body. A
  write that FAILS is the server-side proof that the client cancelled the
  exchange, rather than merely abandoning the result while the download ran on."
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

(defn- start-stalled-body-server!
  "Serve a `total-bytes` body: headers and the first byte at once, the remainder
  only after `release` opens (bounded, so a failing run cannot pin a handler
  thread). `entries` counts exchanges that reached the stall; each exchange then
  conj's `:wrote-all` or `:write-failed` onto `outcomes`. A thread pool lets a
  retry's exchange run while an earlier one is still parked on the latch."
  [^CountDownLatch release total-bytes outcomes entries]
  (let [server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex]
            (try
              (.sendResponseHeaders ex 200 (long total-bytes))
              (let [os    (.getResponseBody ex)
                    chunk (byte-array chunk-size (byte 66))]
                (.write os (int 65))
                (.flush os)
                (swap! entries inc)
                (.await release 10 TimeUnit/SECONDS)
                (try
                  (loop [sent 1]
                    (when (< sent total-bytes)
                      (let [n (min chunk-size (- total-bytes sent))]
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

(deftest managed-attempt-times-out-while-the-body-is-stalled
  (testing "a promptly-headed response whose body stalls fails as :rf.http/timeout
            at the deadline, each retry gets its own budget, and every timed-out
            exchange is cancelled at the host"
    (let [release  (CountDownLatch. 1)
          outcomes (atom [])
          entries  (atom 0)
          replies  (atom [])
          srv      (start-stalled-body-server! release (* 8 1024 1024) outcomes entries)]
      (try
        (rf/reg-event :body/reply
          (fn [_ [_ reply]] (swap! replies conj reply) {}))
        (rf/reg-event :body/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (:url srv)}
                    :decode     :text
                    :timeout-ms 200
                    :retry      {:on           #{:rf.http/timeout}
                                 :max-attempts 2
                                 :backoff      {:base-ms 1 :factor 1 :max-ms 1}}
                    :reply-to   [:body/reply]}]]}))
        (rf/dispatch-sync [:body/load])
        (rf.test-support/poll-until #(seq @replies)
                                    {:timeout-ms 5000 :interval-ms 10
                                     :label "stalled-body timeout reply"})
        (let [reply   (first @replies)
              failure (:error reply)]
          (is (= :error (:status reply)))
          (is (= :rf.http/timeout (:kind failure))
              "the stalled body was bounded by the per-attempt budget")
          (is (= 200 (:limit-ms failure)))
          (is (<= 200 (:elapsed-ms failure) 3000)
              "the timeout fired at the deadline, not whenever the body arrived")
          (is (= 2 (:attempt reply))
              "the retry armed a fresh budget for attempt 2")
          (is (= 2 @entries)
              "both attempts reached the server and stalled mid-body")
          (is (= 1 (.getCount release))
              "the verdict arrived while the body was still being withheld"))
        ;; Release the withheld body. A cancelled exchange makes the server's
        ;; remaining writes fail; an abandoned-but-running one drains happily.
        (.countDown release)
        (rf.test-support/poll-until #(= 2 (count @outcomes))
                                    {:timeout-ms 5000 :interval-ms 10
                                     :label "server-side outcomes"})
        (is (= [:write-failed :write-failed] @outcomes)
            "each timed-out attempt's exchange was cancelled at the host")
        (is (= 1 (count @replies))
            "no late success or duplicate reply followed the timeout")
        (finally
          (.countDown release)
          (stop-server! srv))))))

(deftest non-positive-timeout-keeps-the-documented-opt-out
  (testing ":timeout-ms 0 and nil arm no deadline: a stalled body stays pending,
            then succeeds once released"
    (doseq [timeout-ms [0 nil]]
      (let [release  (CountDownLatch. 1)
            outcomes (atom [])
            entries  (atom 0)
            srv      (start-stalled-body-server! release 2 outcomes entries)]
        (try
          (let [^CompletableFuture cf (rf.http.transport-jvm/jvm-fetch
                                        {:method :get :url (:url srv) :decode :text
                                         :timeout-ms timeout-ms})]
            (rf.test-support/poll-until #(= 1 @entries)
                                        {:timeout-ms 5000 :interval-ms 10
                                         :label "opt-out exchange stalled"})
            (Thread/sleep 150)
            (is (not (.isDone cf))
                (str ":timeout-ms " (pr-str timeout-ms) " armed no deadline"))
            (.countDown release)
            (is (= "AB" (:body-text (.get cf 5 TimeUnit/SECONDS)))
                (str ":timeout-ms " (pr-str timeout-ms) " completes normally once released")))
          (finally
            (.countDown release)
            (stop-server! srv)))))))
