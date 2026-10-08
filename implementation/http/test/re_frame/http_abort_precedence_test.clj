(ns re-frame.http-abort-precedence-test
  "Spec 014 §Abort precedence: abort wins over decode-failure, transport and
  success classification. Each test sequences the contended side instead of
  racing the clock: a latch-gated decoder holds decode mid-run while the abort
  fires (the abort-fn wins the once-only `:finalised?` CAS and the late
  classification bails), or the finalise seam is driven directly on a handle
  whose abort intent is already recorded (the classification wins the CAS and
  is reclassified to `:rf.http/aborted`)."
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
           [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset --------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- start-200-server!
  "Start an HttpServer that always returns 200 with the given body. The
  server processes requests on its default executor; no blocking on the
  server side — the test interleaves on the decoder side via `latches`."
  [content-type body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              bs (.getBytes (str body) "UTF-8")]
                          (when content-type
                            (-> ex .getResponseHeaders (.set "Content-Type" content-type)))
                          (try
                            (.sendResponseHeaders ex 200 (long (count bs)))
                            (with-open [os (.getResponseBody ex)]
                              (.write os bs))
                            (catch Throwable _ nil))))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "timed out awaiting condition" {}))
         :else (do (Thread/sleep 10) (recur)))))))

(def ^:private reply-shape (juxt :status (comp :kind :error) (comp :reason :error)))

(deftest abort-during-in-flight-decode-wins-over-decode-failure
  (let [srv               (start-200-server! "application/json" "{\"k\":1}")
          decoder-entered   (CountDownLatch. 1)
          decoder-may-throw (CountDownLatch. 1)
          replies           (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     (fn [_text _headers]
                                  (.countDown decoder-entered)
                                  (.await decoder-may-throw 30 TimeUnit/SECONDS)
                                  (throw (ex-info "decode-boom" {})))
                    :request-id :race
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/reg-event :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
        (rf/dispatch-sync [:issue])
        (is (.await decoder-entered 5 TimeUnit/SECONDS) "decode is mid-run: the abort window is open")
        (rf/dispatch-sync [:do/abort])
        (.countDown decoder-may-throw)
        (await-condition! #(seq @replies))
        (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
        (is (empty? (rf.http.managed/in-flight-snapshot)))
        (finally
          (stop-server! srv)))))

;; A closed-port request with the abort in the same dispatch would be a clock
;; race (the connection-refused completion runs on the HttpClient's own
;; thread), so the precedence is pinned at the finalise seam instead.
(def ^:private finalise-failure!*
  @#'rf.http.transport/finalise-failure!)

(deftest transport-classification-loses-to-recorded-abort-precedence-seam
  (let [replies (atom [])
        rows    (atom [])
          cb-id   ::precedence-seam-trace]
      (rf.trace.tooling/register-listener! cb-id
        (fn [ev]
          (when (#{:rf.http/aborted :rf.http/transport} (:operation ev))
            (swap! rows conj ev))))
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      ;; A handle as run-attempt! records it, its :aborted? cell pre-flipped:
      ;; the abort intent was recorded before the transport reached finalise.
      (let [finalised? (atom false)
            aborted?   (atom {:reason :user :actor-id nil})
            handle     (rf.http.registry/record-in-flight!
                         :race nil
                         {:abort-fn   (fn [_] nil)
                          :url        "http://127.0.0.1:1/"
                          :finalised? finalised?
                          :aborted?   aborted?
                          :sensitive? false
                          :frame      :rf/default})
            ctx        {:request-id          :race
                        :url                 "http://127.0.0.1:1/"
                        :handle              handle
                        :frame               :rf/default
                        :sensitive?          false
                        :origin-event        [:issue]
                        :explicit-on-failure {:supplied? true :value [:reply/recorder]}}]
        (try
          (finalise-failure!* ctx {:kind :rf.http/transport :message "Connection refused" :cause "java.net.ConnectException"})
          (finally
            (rf.trace.tooling/unregister-listener! cb-id)))
        (is (= [[:rf.http/aborted :info :user]]
               (mapv (juxt :operation :op-type (comp :reason :tags)) @rows))
            "one :info :rf.http/aborted row, and no :rf.http/transport error row")
        (await-condition! #(seq @replies))
        (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
        (is (empty? (rf.http.managed/in-flight-snapshot))))))

(deftest abort-via-actor-destroy-wins-over-decode-failure
  (let [srv               (start-200-server! "application/json" "{\"k\":1}")
          decoder-entered   (CountDownLatch. 1)
          decoder-may-throw (CountDownLatch. 1)
          replies           (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-machine :worker/race
          {:initial :idle
           :data    {:port (:port srv)}
           :actions {:fire (fn [{data :data}]
                             {:fx [[:rf.http/managed
                                    {:request    {:url (str "http://127.0.0.1:" (:port data) "/")}
                                     :decode     (fn [_text _headers]
                                                   (.countDown decoder-entered)
                                                   (.await decoder-may-throw 30 TimeUnit/SECONDS)
                                                   (throw (ex-info "decode-boom" {})))
                                     :request-id :race
                                     :on-failure [:reply/recorder]
                                     :on-success [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire}}})
        (rf/reg-machine :sup/race
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:spawn {:machine-id :worker/race
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/race [:start]])
        (is (.await decoder-entered 5 TimeUnit/SECONDS) "decode is mid-run under the spawned actor")
        (is (= 1 (count (rf.http.managed/actor-in-flight-snapshot)))
            "the actor index holds the handle while the response decodes")
        ;; Destroying the actor walks the actor index and aborts with :actor-destroyed.
        (rf/dispatch-sync [:sup/race [:cancel]])
        (.countDown decoder-may-throw)
        (await-condition! #(seq @replies))
        (is (= [[:cancelled :rf.http/aborted :actor-destroyed]] (mapv reply-shape @replies)))
        (is (empty? (rf.http.managed/actor-in-flight-snapshot)))
        (finally
          (stop-server! srv)))))
