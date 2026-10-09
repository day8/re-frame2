(ns re-frame.ssr.ring.streaming-robustness-test
  "The streaming writer thread always ends: it is a named daemon, it is never
  spawned for a head that cannot materialise, and a body nobody drains stops
  it at the stall limit while a slow reader still gets the whole page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private leak-poll-ms 10)

(defn- stream-handler
  "`stream-handler` over a no-op server init event, merged with `opts`."
  [opts]
  (rf/reg-event :rf.test.server/init {:platforms #{:server}} (fn [_ _] {}))
  (rf.ssr.ring/stream-handler
    (merge {:initial-events [[:rf.test.server/init]]
            :payload        :rf.ssr.payload/whole-app-db}
           opts)))

(defn- page-of
  "A fn `:root-view` rendering `rows` paragraphs of about 38 bytes each."
  [rows]
  (fn [] (into [:div] (for [i (range rows)] [:p (str "row-" i "-padding-padding-padding")]))))

(deftest daemon-thread-name-is-frame-scoped
  (testing "the writer is a daemon thread named rf2-ssr-streaming-*, the prefix
            every leak check in this suite scopes by"
    (let [parked  (CountDownLatch. 1)
          release (CountDownLatch. 1)]
      (rf/reg-view ^{:rf/id :test/parking-section} parking-section []
        (.countDown parked)
        (.await release 5 TimeUnit/SECONDS)
        [:p "released"])
      (let [response ((stream-handler
                        {:root-view [:main
                                     [:rf/suspense-boundary {:id :test/parker :fallback [:p "loading"]}
                                      [(rf/view :test/parking-section)]]]})
                      {:uri "/" :request-method :get})
            drain    (future (with-open [^InputStream is (:body response)] (slurp is)))
            live     (try
                       (.await parked 5 TimeUnit/SECONDS)
                       (rf.ssr.ring.test-support/live-streaming-threads)
                       (finally
                         (.countDown release)
                         @drain))]
        (is (seq live))
        (is (every? (fn [^Thread t] (.isDaemon t)) live))))))

(deftest head-materialisation-throw-does-not-orphan-writer
  (testing "a cookie the fx gate admits but the head materialiser refuses (a
            non-integer :expires) answers :on-error before any writer exists;
            the page outgrows the pipe, so a writer spawned first would block"
    (rf/reg-event :rf.test.server/init-bad-cookie
      {:platforms #{:server}}
      (fn [_ _] {:fx [[:rf.server/set-cookie {:name "s" :value "v" :expires "not-an-int"}]]}))
    (let [response ((stream-handler {:initial-events [[:rf.test.server/init-bad-cookie]]
                                     :root-view      (page-of 4000)})
                     {:uri "/" :request-method :get})]
      (is (= {:status 500 :body "Internal error"} (select-keys response [:status :body])))
      (is (empty? (rf.ssr.ring.test-support/await-no-streaming-threads! 5000 leak-poll-ms))))))

;; ---- a body nobody drains -------------------------------------------------
;;
;; Ring's `wrap-head` drops the streamed body of every HEAD unread and unclosed.
;; Past the 16 KiB pipe the writer would park for the life of the JVM, holding
;; its thread, the request frame and the request slot; the stall limit
;; (`streaming/stall-timeout-ms`, 60 s, redefined to `stall-limit-ms` here)
;; ends it. Counts are read in the test body, before the fixture's reset would
;; hide a leak, and every test closes the bodies it captured.

(def ^:private stall-limit-ms 500)

(defn- recording-stream-handler
  "A `stream-handler` over a 1000-row page that records each body into
  `bodies`, so a test can close a body the middleware under test dropped."
  [bodies]
  (let [handler (stream-handler {:root-view (page-of 1000)})]
    (fn [request]
      (let [response (handler request)]
        (swap! bodies conj (:body response))
        response))))

(defn- close-bodies! [bodies]
  (doseq [body @bodies]
    (when (instance? InputStream body)
      (.close ^InputStream body))))

(defn- leak-census
  "Live writer threads, live `:rf.frame/*` request frames, filled request slots."
  []
  {:writers (count (rf.ssr.ring.test-support/live-streaming-threads))
   :frames  (count (rf.frame/frame-ids "rf.frame"))
   :slots   (count @rf.ssr.request/request-slots)})

(def ^:private no-leak {:writers 0 :frames 0 :slots 0})

(defn- await-no-leak!
  "Poll `leak-census` until it reads `no-leak` or `timeout-ms` elapses; return
  the last reading."
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [census (leak-census)]
        (if (or (= no-leak census) (>= (System/currentTimeMillis) deadline))
          census
          (do (Thread/sleep (long leak-poll-ms)) (recur)))))))

(deftest wrap-head-dropped-body-is-reclaimed-within-the-stall-limit
  (testing "the writer, the request frame and the request slot behind a body
            wrap-head dropped are reclaimed at the stall limit, with one
            always-on record of the timeout"
    (let [bodies  (atom [])
          records (atom [])
          app     (ring.head/wrap-head (recording-stream-handler bodies))]
      (rf.error-emit/register-error-listener! ::stall #(swap! records conj %))
      (try
        (with-redefs [rf.ssr.ring.streaming/stall-timeout-ms stall-limit-ms]
          (app {:request-method :head :uri "/"})
          (is (= no-leak (await-no-leak! 3000)) "without the limit: 1 / 1 / 1, for ever")
          (is (= ["java.util.concurrent.TimeoutException"]
                 (->> @records
                      (filter #(= :rf.error/ssr-streaming-writer-failed (:error %)))
                      (mapv :ex-class)))))
        (finally
          (rf.error-emit/unregister-error-listener! ::stall)
          (close-bodies! bodies))))))

(deftest steady-slow-reader-gets-the-whole-body-over-longer-than-the-limit
  (testing "control: the limit measures no progress, not total time — a reader
            taking 4 KiB every 150 ms gets the whole page over well past it"
    (let [bodies  (atom [])
          handler (recording-stream-handler bodies)]
      (try
        (with-redefs [rf.ssr.ring.streaming/stall-timeout-ms stall-limit-ms]
          (let [^InputStream in (:body (handler {:request-method :get :uri "/"}))
                out   (java.io.ByteArrayOutputStream.)
                buf   (byte-array 4096)
                start (System/currentTimeMillis)]
            (loop []
              (let [n (.read in buf 0 4096)]
                (when (pos? n)
                  (.write out buf 0 n)
                  (Thread/sleep 150)
                  (recur))))
            (is (> (- (System/currentTimeMillis) start) (* 2 stall-limit-ms)))
            (is (str/ends-with? (.toString out "UTF-8") "</body></html>"))))
        (finally (close-bodies! bodies))))))

(deftest payload-number-refusal-truncates-the-stream-and-reclaims-everything
  (testing "a final payload carrying a Long past 2^53 is refused after the 200
            committed (ssr-handler refuses it before committing): the body stops
            before any payload, the writer reports the refusal, and nothing
            outlives the truncated response"
    (rf/reg-event :rf.test.server/init-wide-order-id
      {:platforms #{:server}}
      (fn [_ _] {:db {:order {:id 9007199254740993}}}))
    (let [handler (stream-handler {:initial-events [[:rf.test.server/init-wide-order-id]]
                                   :root-view      [:div "page"]
                                   :payload        [:order]})]
      (with-trace-recorder! [captured]
        (let [response (handler {:request-method :get :uri "/order"})
              html     (with-open [^InputStream is (:body response)] (slurp is))
              failures (filterv #(= :rf.error/ssr-streaming-writer-failed (:operation %))
                                @captured)]
          (is (= 200 (:status response)))
          (is (str/includes? html "page"))
          (is (not-any? #(str/includes? html %) ["__rf_payload" "9007199254740993"]))
          (is (= 1 (count failures)))
          (is (str/ends-with? (-> failures first :tags :exception)
                              "[:rf.error/ssr-hydration-payload-invalid]"))
          (is (= no-leak (await-no-leak! 3000))))))))
