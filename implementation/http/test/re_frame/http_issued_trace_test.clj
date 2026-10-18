(ns re-frame.http-issued-trace-test
  "The `:rf.http/issued` trace row and the numbering of issuances
  (Managed-Effects §Tracing, §Work-id correlation).

    1. ISSUANCE ROW — every `:rf.http/managed` issuance emits exactly one
       `:rf.http/issued` `:info` row inside the issuing fx handler, so it sits
       in the issuing run's bundle and precedes every row the issuance causes.
       It carries the ATTEMPT-1 work-id; a completion carries the attempt that
       completed, so the two join on the two-element issuance prefix
       `[:rf.work/http issuance]`.
    2. ONE COUNTER PER FRAME — every issuance in a frame, named or anonymous,
       takes the next number from the frame's one monotone counter, so no two
       requests a frame issues share a work id `[:rf.work/http issuance
       attempt]`. Completion and epoch restore leave the counter alone; frame
       destroy drops it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent CompletableFuture CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- real-transport server harness ----------------------------------------

(defn- start-server!
  "Answer every exchange `200 {\"v\":1}`, first awaiting the latch `hold-for`
  returns for the exchange's path (nil → answer at once). Holding per path is
  what lets a test choose the ORDER in which two in-flight requests complete."
  [hold-for]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex]
            (when-let [^CountDownLatch latch (hold-for (.getPath (.getRequestURI ex)))]
              (try (.await latch 30 TimeUnit/SECONDS)
                   (catch InterruptedException _ nil)))
            (try
              (let [bytes (.getBytes "{\"v\":1}" "UTF-8")]
                (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
                (.sendResponseHeaders ex 200 (long (count bytes)))
                (with-open [os (.getResponseBody ex)] (.write os bytes)))
              ;; a superseded request's connection may already be gone
              (catch java.io.IOException _ nil))))))
    ;; one thread per exchange, so a held exchange cannot block its sibling
    (.setExecutor server (java.util.concurrent.Executors/newCachedThreadPool))
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}] (.stop ^HttpServer server 0))

(defn- url [srv path] (str "http://127.0.0.1:" (:port srv) path))

(defn- with-traces
  "Run `(f traces)` with a listener capturing every trace row into `traces`."
  [f]
  (let [traces (atom [])
        lid    (keyword (str (gensym "issued-trace-")))]
    (rf.trace.tooling/register-listener! lid (fn [ev] (swap! traces conj ev)))
    (try (f traces)
         (finally (rf.trace.tooling/unregister-listener! lid)))))

(defn- rows [traces op] (filterv #(= op (:operation %)) @traces))
(defn- dispatch-id [row] (get-in row [:tags :rf.trace/dispatch-id]))
(defn- work-id [row] (get-in row [:tags :rf.reply/work-id]))
(defn- prefix [wid] (subvec wid 0 2))

(defn- await-latch! [^CountDownLatch l]
  (.await l 30 TimeUnit/SECONDS))

;; ---- the issued row ----------------------------------------------------------

(deftest named-request-emits-one-issued-row-in-the-issuing-bundle
  (let [srv  (start-server! (constantly nil))
        done (CountDownLatch. 1)]
    (try
      (with-traces
        (fn [traces]
          (rf/reg-event :t/done (fn [_ _] (.countDown done) {}))
          (rf/reg-event :t/load
            (fn [_ _]
              {:fx [[:rf.http/managed {:request    {:url (url srv "/t") :method :get}
                                       :request-id :t/load
                                       :decode     :json
                                       :reply-to   [:t/done {:secret "arg"}]}]]}))
          (rf/dispatch-sync [:t/load])
          (is (await-latch! done) "the reply was delivered")
          (let [[row :as issued] (rows traces :rf.http/issued)
                handled          (first (filter #(= :rf.http/managed (get-in % [:tags :rf.fx/id]))
                                                (rows traces :rf.fx/handled)))]
            (is (= 1 (count issued)) "exactly one :rf.http/issued row per issuance")
            (is (= {:rf.reply/work-id   [:rf.work/http 1 1]
                    :rf.reply/work-kind :http
                    :request-id         :t/load
                    :method             :get
                    :url                (url srv "/t")
                    :frame              :rf/default
                    :reply-to           {:on-success :t/done :on-failure :t/done}}
                   (select-keys (:tags row) [:rf.reply/work-id :rf.reply/work-kind :request-id
                                             :method :url :frame :reply-to]))
                "the target summary names the reply event-id(s), never the event's args")
            (is (= (dispatch-id handled) (dispatch-id row))
                "the issued row lands in the ISSUING run's bundle")
            (is (< (:id row) (:id handled)) "the issued row precedes the fx's :rf.fx/handled row")
            (is (= (prefix (work-id row)) (prefix (work-id (first (rows traces :rf.http/replied)))))
                "the issued row joins its completion row on the issuance prefix"))))
      (finally (stop-server! srv)))))

(deftest superseded-named-request-emits-an-issued-row-per-issuance
  (let [release (CountDownLatch. 1)
        done    (CountDownLatch. 1)
        srv     (start-server! (constantly release))]
    (try
      (with-traces
        (fn [traces]
          (rf/reg-event :s/done (fn [_ _] (.countDown done) {}))
          (rf/reg-event :s/go
            (fn [_ _]
              {:fx [[:rf.http/managed {:request    {:url (url srv "/s")}
                                       :request-id :search
                                       :decode     :json
                                       :reply-to   [:s/done]}]]}))
          ;; the held server keeps #1 in flight, so #2 genuinely supersedes it
          (rf/dispatch-sync [:s/go])
          (rf/dispatch-sync [:s/go])
          (.countDown release)
          (is (await-latch! done) "the superseding request's reply was delivered")
          (let [issued (rows traces :rf.http/issued)
                ok     (filterv #(= :ok (get-in % [:tags :status])) (rows traces :rf.http/replied))]
            (is (= [[:rf.work/http 1 1] [:rf.work/http 2 1]]
                   (mapv work-id issued)))
            (is (< (:id (second issued)) (:id (first (rows traces :rf.http/stale-suppressed))))
                "the superseder's issued row precedes the stale-suppression it causes")
            (is (= [[:rf.work/http 2 1]] (mapv work-id ok))
                "the delivered completion joins the SECOND issued row"))))
      (finally
        (.countDown release)
        (stop-server! srv)))))

(deftest sequential-named-loads-carry-distinct-work-ids
  (testing "the same request id loaded twice in sequence — the first completed
            before the second is issued — takes two numbers, so the two
            completions never share a work id"
    (let [srv     (start-server! (constantly nil))
          replies (atom [])]
      (try
        (with-traces
          (fn [traces]
            (rf/reg-event :seq/done
              (fn [_ [_ latch reply]]
                (swap! replies conj (:rf.reply/work-id reply))
                (.countDown ^CountDownLatch latch)
                {}))
            (rf/reg-event :seq/load
              (fn [_ [_ latch]]
                {:fx [[:rf.http/managed {:request    {:url (url srv "/seq")}
                                         :request-id :search
                                         :decode     :json
                                         :reply-to   [:seq/done latch]}]]}))
            (doseq [latch [(CountDownLatch. 1) (CountDownLatch. 1)]]
              (rf/dispatch-sync [:seq/load latch])
              (is (await-latch! latch) "the load completed before the next was issued"))
            (is (apply distinct? @replies) "the two loads' work ids differ")
            (is (= [[:rf.work/http 1 1] [:rf.work/http 2 1]]
                   (mapv work-id (rows traces :rf.http/issued))))
            (is (= [[:rf.work/http 1 1] [:rf.work/http 2 1]] @replies)
                "each completion carries its own load's work id")))
        (finally (stop-server! srv))))))

;; Two overlapping anonymous requests of one event-id, completing in REVERSE
;; order: each takes the next number and keeps it whatever the completion order.
(deftest overlapping-anonymous-requests-carry-distinct-work-ids
  (let [latches {"/a" (CountDownLatch. 1) "/b" (CountDownLatch. 1)}
        got-a   (CountDownLatch. 1)
        got-b   (CountDownLatch. 1)
        replies (atom {})
        srv     (start-server! latches)]
    (try
      (with-traces
        (fn [traces]
          (rf/reg-event :anon/done
            (fn [_ [_ path reply]]
              (swap! replies assoc path (:rf.reply/work-id reply))
              (.countDown ^CountDownLatch (if (= "/a" path) got-a got-b))
              {}))
          (rf/reg-event :anon/go
            (fn [_ [_ path]]
              {:fx [[:rf.http/managed {:request  {:url (url srv path)}
                                       :decode   :json
                                       :reply-to [:anon/done path]}]]}))
          (rf/dispatch-sync [:anon/go "/a"])
          (rf/dispatch-sync [:anon/go "/b"])
          (.countDown ^CountDownLatch (latches "/b"))
          (is (await-latch! got-b))
          (.countDown ^CountDownLatch (latches "/a"))
          (is (await-latch! got-a))
          (is (= [[:rf.work/http 1 1] [:rf.work/http 2 1]]
                 (mapv work-id (rows traces :rf.http/issued)))
              "each anonymous issuance takes the frame's next number")
          (is (= {"/a" [:rf.work/http 1 1]
                  "/b" [:rf.work/http 2 1]}
                 @replies)
              "each completion carries its own issuance, whatever the completion order")))
      (finally
        (doseq [l (vals latches)] (.countDown ^CountDownLatch l))
        (stop-server! srv)))))

(deftest retried-request-joins-its-issued-row-on-the-issuance-prefix
  (let [hits   (atom 0)
        done   (CountDownLatch. 1)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setHandler (.createContext server "/")
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex
                first-hit?       (= 1 (swap! hits inc))
                bytes            (.getBytes "{\"v\":1}" "UTF-8")]
            (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
            (.sendResponseHeaders ex (if first-hit? 503 200) (long (count bytes)))
            (with-open [os (.getResponseBody ex)] (.write os bytes))))))
    (.start server)
    (try
      (with-traces
        (fn [traces]
          (rf/reg-event :retry/done (fn [_ _] (.countDown done) {}))
          (rf/reg-event :retry/go
            (fn [_ _]
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/r")}
                      :request-id :retry/one
                      :decode     :json
                      :retry      {:on           #{:rf.http/http-5xx}
                                   :max-attempts 2
                                   :backoff      {:base-ms 1 :factor 1 :max-ms 1}}
                      :reply-to   [:retry/done]}]]}))
          (rf/dispatch-sync [:retry/go])
          (is (await-latch! done))
          (is (= [[:rf.work/http 1 1]] (mapv work-id (rows traces :rf.http/issued)))
              "one issued row per issuance, however many attempts, carrying attempt 1")
          (is (= [:rf.work/http 1 2] (work-id (first (rows traces :rf.http/replied))))
              "the completion carries its own attempt")))
      (finally (.stop server 0)))))

(deftest issued-row-precedes-a-synchronous-body-prep-failure
  (with-traces
    (fn [traces]
      (rf/reg-event :prep/done (fn [_ _] {}))
      (rf/reg-event :prep/go
        (fn [_ _]
          {:fx [[:rf.http/managed {:request    {:url    "http://127.0.0.1:0/x?q=hunter2"
                                                :method :post
                                                :body   (fn [] (throw (ex-info "boom-thunk" {})))}
                                   :request-id :prep
                                   :sensitive? true
                                   :reply-to   [:prep/done]}]]}))
      (rf/dispatch-sync [:prep/go])
      (let [issued  (first (rows traces :rf.http/issued))
            failure (first (rows traces :rf.http/transport))]
        (is (< (:id issued) (:id failure)) "the issued row precedes the failure row")
        (is (= (dispatch-id issued) (dispatch-id failure))
            "both rows sit in the issuing bundle")
        (is (= [true "http://127.0.0.1:0/x?q=:rf/redacted"] [(:sensitive? issued) (get-in issued [:tags :url])])
            "a sensitive request's issued row is stamped and its query value scrubbed")))))

(deftest a-restore-never-recycles-a-work-id
  (testing "a request issued after an epoch restore takes the frame's NEXT
            number, never one the unwound timeline already used — even though
            the restore cancelled the earlier request"
    (let [f :issued/restore]
      (rf/make-frame {:id f})
      (rf/reg-event :restore/seed (fn [{:keys [db]} _] {:db (assoc db :seeded true)}))
      (rf/reg-event :restore/done (fn [_ _] {}))
      (rf/reg-event :restore/load
        (fn [_ _]
          {:fx [[:rf.http/managed {:request    {:url "http://127.0.0.1:0/r"}
                                   :request-id :search
                                   :reply-to   [:restore/done]}]]}))
      (with-traces
        (fn [traces]
          (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_] (CompletableFuture.))]
            (rf/dispatch-sync [:restore/seed] {:frame f})
            (let [before-load (:epoch-id (last (rf.epoch/epoch-history f)))]
              (rf/dispatch-sync [:restore/load] {:frame f})
              (is (:ok? (rf.epoch/restore-epoch! f before-load)))
              (is (empty? (rf.http.registry/in-flight-snapshot f))
                  "PRECONDITION: the restore cancelled the earlier request")
              (rf/dispatch-sync [:restore/load] {:frame f})
              (is (apply distinct? (mapv work-id (rows traces :rf.http/issued)))
                  "the load after the restore did not reuse the cancelled load's work id")
              (is (= [[:rf.work/http 1 1] [:rf.work/http 2 1]]
                     (mapv work-id (rows traces :rf.http/issued))))))))
      (rf/destroy-frame! f))))

;; ---- registry altitude --------------------------------------------------------

(deftest issuance-counter-is-one-monotone-sequence-per-frame
  (rf.http.registry/reset-issuance-counters-for-test!)
  (is (= [1 2 1 3] [(rf.http.registry/next-issuance! :frame/f)
                    (rf.http.registry/next-issuance! :frame/f)
                    (rf.http.registry/next-issuance! :frame/g)
                    (rf.http.registry/next-issuance! :frame/f)])
      "every issuance takes its frame's next number; a sibling frame runs its own")
  (is (= 2 (rf.http.registry/issuance-counter-count)) "one counter per frame"))

(deftest frame-destroy-drops-that-frames-counter
  (rf.http.registry/reset-issuance-counters-for-test!)
  (rf.http.registry/next-issuance! :frame/gone)
  (rf.http.registry/next-issuance! :frame/kept)
  (is (= 2 (rf.http.registry/issuance-counter-count)))
  (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/gone)
  (is (= 1 (rf.http.registry/issuance-counter-count))
      "only the destroyed frame's counter is dropped")
  (is (= 2 (rf.http.registry/next-issuance! :frame/kept))
      "the sibling frame's sequence is untouched"))
