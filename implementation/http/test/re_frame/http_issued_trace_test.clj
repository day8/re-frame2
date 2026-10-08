(ns re-frame.http-issued-trace-test
  "The `:rf.http/issued` trace row and the numbering of ANONYMOUS issuances
  (Managed-Effects §Tracing).

    1. ISSUANCE ROW — every `:rf.http/managed` issuance emits exactly one
       `:rf.http/issued` `:info` row inside the issuing fx handler, so it sits
       in the issuing run's bundle and precedes every row the issuance causes.
       It carries the ATTEMPT-1 work-id; a completion carries the attempt that
       completed, so the two join on the three-element issuance prefix
       `[:rf.work/http logical-id issuance]`.
    2. ANONYMOUS NUMBERING — a request with no `:request-id` is numbered by a
       never-evicted per-(frame, event-id) counter, and its logical id is
       TAGGED `[:rf.http/anonymous event-id]` so it never equals a named
       request's `:request-id` (the two counters are independent)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

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
(defn- prefix [wid] (subvec wid 0 3))

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
            (is (= {:rf.reply/work-id   [:rf.work/http :t/load 1 1]
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
            (is (= [[:rf.work/http :search 1 1] [:rf.work/http :search 2 1]]
                   (mapv work-id issued)))
            (is (< (:id (second issued)) (:id (first (rows traces :rf.http/stale-suppressed))))
                "the superseder's issued row precedes the stale-suppression it causes")
            (is (= [[:rf.work/http :search 2 1]] (mapv work-id ok))
                "the delivered completion joins the SECOND issued row"))))
      (finally
        (.countDown release)
        (stop-server! srv)))))

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
          (is (= [[:rf.work/http [:rf.http/anonymous :anon/go] 1 1]
                  [:rf.work/http [:rf.http/anonymous :anon/go] 2 1]]
                 (mapv work-id (rows traces :rf.http/issued)))
              "each anonymous issuance of one event-id in one frame takes the next number")
          (is (= {"/a" [:rf.work/http [:rf.http/anonymous :anon/go] 1 1]
                  "/b" [:rf.work/http [:rf.http/anonymous :anon/go] 2 1]}
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
          (is (= [[:rf.work/http :retry/one 1 1]] (mapv work-id (rows traces :rf.http/issued)))
              "one issued row per issuance, however many attempts, carrying attempt 1")
          (is (= [:rf.work/http :retry/one 1 2] (work-id (first (rows traces :rf.http/replied))))
              "the completion carries its own attempt")))
      (finally (.stop server 0)))))

(deftest issued-row-precedes-a-synchronous-body-prep-failure
  (with-traces
    (fn [traces]
      (rf/reg-event :prep/done (fn [_ _] {}))
      (rf/reg-event :prep/go
        (fn [_ _]
          {:fx [[:rf.http/managed {:request    {:url    "http://127.0.0.1:0/x"
                                                :method :post
                                                :body   (fn [] (throw (ex-info "boom-thunk" {})))}
                                   :request-id :prep
                                   :reply-to   [:prep/done]}]]}))
      (rf/dispatch-sync [:prep/go])
      (let [issued  (first (rows traces :rf.http/issued))
            failure (first (rows traces :rf.http/transport))]
        (is (< (:id issued) (:id failure)) "the issued row precedes the failure row")
        (is (= (dispatch-id issued) (dispatch-id failure))
            "both rows sit in the issuing bundle")))))

(deftest sensitive-request-issued-row-redacts-the-url
  (with-traces
    (fn [traces]
      (rf/reg-event :priv/done (fn [_ _] {}))
      (rf/reg-event :priv/go
        (fn [_ _]
          {:fx [[:rf.http/managed {:request    {:url    "http://127.0.0.1:0/x?q=hunter2"
                                                :method :post
                                                :body   (fn [] (throw (ex-info "stop-here" {})))}
                                   :sensitive? true
                                   :reply-to   [:priv/done]}]]}))
      (rf/dispatch-sync [:priv/go])
      (let [row (first (rows traces :rf.http/issued))]
        (is (true? (:sensitive? row)) "the row is stamped sensitive")
        (is (= "http://127.0.0.1:0/x?q=:rf/redacted" (get-in row [:tags :url]))
            "the query value is scrubbed from the url")))))

;; ---- registry altitude --------------------------------------------------------

(deftest anonymous-counter-is-never-evicted
  (testing "anonymous a=1, b=2; b completes (evict called exactly as the
  transport calls it, with a nil request-id); the next anonymous issuance
  reads 3, not 1 — a compare-and-drop eviction would reissue 1 while a
  is still live"
    (rf.http.registry/reset-issuance-counters-for-test!)
    (let [a (rf.http.registry/next-issuance! :frame/f nil [:ev/go])
          b (rf.http.registry/next-issuance! :frame/f nil [:ev/go])]
      (is (= [1 2] [a b]))
      (rf.http.registry/evict-issuance-on-completion! :frame/f nil b)
      (is (= 3 (rf.http.registry/next-issuance! :frame/f nil [:ev/go]))))))

(deftest anonymous-counter-is-bounded-by-frame-x-event
  (testing "N anonymous issuances of one event hold ONE entry, and never touch the named map"
    (rf.http.registry/reset-issuance-counters-for-test!)
    (dotimes [_ 50] (rf.http.registry/next-issuance! :frame/f nil [:ev/go]))
    (is (= 1 (rf.http.registry/anonymous-issuance-counter-count))
        "one entry per (frame, event-id), not one per request")
    (is (zero? (rf.http.registry/issuance-counter-count))
        "the named-counter leak instrument counts named requests only")))

(deftest frame-destroy-drops-that-frames-anonymous-counters
  (rf.http.registry/reset-issuance-counters-for-test!)
  (rf.http.registry/next-issuance! :frame/gone nil [:ev/go])
  (rf.http.registry/next-issuance! :frame/kept nil [:ev/go])
  (is (= 2 (rf.http.registry/anonymous-issuance-counter-count)))
  (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/gone)
  (is (= 1 (rf.http.registry/anonymous-issuance-counter-count))
      "only the destroyed frame's entries are dropped")
  (is (= 2 (rf.http.registry/next-issuance! :frame/kept nil [:ev/go]))
      "the sibling frame's sequence is untouched"))
