(ns re-frame.http-issued-trace-test
  "rf2-x8oz5 — the `:rf.http/issued` trace row and per-(frame, event-id)
  numbering of ANONYMOUS issuances (Managed-Effects §Tracing: a managed async
  family MUST emit an issuance/start row carrying `:work/id`, frame and target
  summary).

  Two properties, pinned end-to-end through the real
  `java.net.http.HttpClient` transport and at the registry altitude:

    1. ISSUANCE ROW — every `:rf.http/managed` issuance emits exactly one
       `:rf.http/issued` `:info` row, inside the issuing fx handler, so it
       carries the issuing run's `:rf.trace/dispatch-id` and PRECEDES every
       row the issuance causes (the `:rf.fx/handled` row for the same fx, a
       synchronous body-prep failure, the stale-suppression of a superseded
       predecessor). Its `:rf.reply/work-id` is the ATTEMPT-1 work-id; a
       completion row carries the attempt that completed, so a tool pairs an
       issuance with its completion on the three-element issuance PREFIX
       `[:rf.work/http logical-id issuance]`, never on full equality
       (rf2-ojn0y).
    2. ANONYMOUS NUMBERING — a request with no `:request-id` takes its
       issuance number from a per-(frame, originating event-id) counter that
       is NEVER evicted, so two anonymous requests of one event in one frame
       carry distinct work-ids (`[:rf.work/http [:rf.http/anonymous ev] 1 1]`,
       `[:rf.work/http [:rf.http/anonymous ev] 2 1]`) however their completions
       interleave. The anonymous logical-id is TAGGED (rf2-5g0bt) so it can
       never equal a named request's `:request-id` — the two counters are
       independent, so untagged they would collide at the same number.
       Named requests are unchanged: the per-(frame, request-id) counter with its conditional
       eviction (rf2-k47b3d)."
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
(defn- managed-handled [traces]
  (filterv #(= :rf.http/managed (get-in % [:tags :rf.fx/id])) (rows traces :rf.fx/handled)))

(defn- await-latch! [^CountDownLatch l]
  (.await l 30 TimeUnit/SECONDS))

;; ===========================================================================
;; G1 — a named request: one issued row in the issuing bundle, joinable to
;; its completion row by work-id.
;; ===========================================================================

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
          (let [issued  (rows traces :rf.http/issued)
                handled (managed-handled traces)
                replied (rows traces :rf.http/replied)]
            (is (= 1 (count issued)) "exactly one :rf.http/issued row per issuance")
            (is (= 1 (count handled)) "control: one :rf.fx/handled row for the :rf.http/managed fx")
            (let [row (first issued)
                  tags (:tags row)]
              (is (= :info (:op-type row)))
              (is (some? (dispatch-id row)) "the row carries a dispatch-id (not filed under [nil :ungrouped])")
              (is (= (dispatch-id (first handled)) (dispatch-id row))
                  "the issued row lands in the ISSUING run's bundle — the same run as its :rf.fx/handled row")
              (is (< (:id row) (:id (first handled)))
                  "the issued row precedes the fx's :rf.fx/handled row")
              (is (= [:rf.work/http :t/load 1 1] (work-id row)))
              (is (= :http (:rf.reply/work-kind tags)))
              (is (= :t/load (:request-id tags)))
              (is (= :get (:method tags)))
              (is (= (url srv "/t") (:url tags)))
              (is (= :rf/default (:frame tags)))
              (is (= {:on-success :t/done :on-failure :t/done} (:reply-to tags))
                  "the target summary names the reply event-id(s), never the event's args")
              (is (= 1 (count replied)))
              (is (= (subvec (work-id row) 0 3) (subvec (work-id (first replied)) 0 3))
                  "the issued row joins its completion row on the issuance prefix"))))
        )
      (finally (stop-server! srv)))))

;; ===========================================================================
;; G2 — a named request superseded and re-issued.
;; ===========================================================================

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
                stale  (rows traces :rf.http/stale-suppressed)
                ok     (filterv #(= :ok (get-in % [:tags :status])) (rows traces :rf.http/replied))]
            (is (= [[:rf.work/http :search 1 1] [:rf.work/http :search 2 1]]
                   (mapv work-id issued)))
            (is (= 1 (count stale)))
            (is (= [:rf.work/http :search 1 1]
                   (:work/id (get-in (first stale) [:tags :rf.reply/carried])))
                "the stale-suppression row carries the superseded issuance")
            (is (< (:id (second issued)) (:id (first stale)))
                "the superseder's issued row precedes the stale-suppression it causes")
            (is (= [[:rf.work/http :search 2 1]] (mapv work-id ok))
                "the delivered completion joins the SECOND issued row"))))
      (finally
        (.countDown release)
        (stop-server! srv)))))

;; ===========================================================================
;; G3 — two OVERLAPPING anonymous requests of one event-id from two runs,
;; completing in REVERSE order.
;; ===========================================================================

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
          ;; b completes FIRST, then a
          (.countDown ^CountDownLatch (latches "/b"))
          (is (await-latch! got-b))
          (.countDown ^CountDownLatch (latches "/a"))
          (is (await-latch! got-a))
          (let [issued (rows traces :rf.http/issued)]
            (is (= [[:rf.work/http [:rf.http/anonymous :anon/go] 1 1] [:rf.work/http [:rf.http/anonymous :anon/go] 2 1]]
                   (mapv work-id issued))
                "each anonymous issuance of one event-id in one frame takes the next number")
            (is (nil? (get-in (first issued) [:tags :request-id])))
            (is (not= (dispatch-id (first issued)) (dispatch-id (second issued)))
                "each issued row lands in its own issuing run"))
          (is (= {"/a" [:rf.work/http [:rf.http/anonymous :anon/go] 1 1]
                  "/b" [:rf.work/http [:rf.http/anonymous :anon/go] 2 1]}
                 @replies)
              "each completion carries its own issuance, whatever the completion order")
          (is (= #{[:rf.work/http [:rf.http/anonymous :anon/go] 1 1] [:rf.work/http [:rf.http/anonymous :anon/go] 2 1]}
                 (set (map work-id (rows traces :rf.http/replied))))
              "the two completion rows are distinguishable by work-id")))
      (finally
        (doseq [l (vals latches)] (.countDown ^CountDownLatch l))
        (stop-server! srv)))))

(deftest named-requests-under-distinct-ids-carry-distinct-work-ids
  (testing "control for the anonymous case above: distinct request-ids were always distinct"
    (let [done    (CountDownLatch. 2)
          replies (atom #{})
          srv     (start-server! (constantly nil))]
      (try
        (rf/reg-event :named/done
          (fn [_ [_ reply]] (swap! replies conj (:rf.reply/work-id reply)) (.countDown done) {}))
        (rf/reg-event :named/go
          (fn [_ [_ rid]]
            {:fx [[:rf.http/managed {:request    {:url (url srv "/n")}
                                     :request-id rid
                                     :decode     :json
                                     :reply-to   [:named/done]}]]}))
        (rf/dispatch-sync [:named/go :n/one])
        (rf/dispatch-sync [:named/go :n/two])
        (is (await-latch! done))
        (is (= #{[:rf.work/http :n/one 1 1] [:rf.work/http :n/two 1 1]} @replies))
        (finally (stop-server! srv))))))

;; ===========================================================================
;; G4 — two anonymous requests issued by ONE run: issued(k) precedes
;; handled(k), and the pairs are ordered.
;; ===========================================================================

(deftest two-anonymous-requests-in-one-run-pair-ordinally-with-their-handled-rows
  (let [done (CountDownLatch. 2)
        srv  (start-server! (constantly nil))]
    (try
      (with-traces
        (fn [traces]
          (rf/reg-event :pair/done (fn [_ _] (.countDown done) {}))
          (rf/reg-event :pair/go
            (fn [_ _]
              {:fx [[:rf.http/managed {:request {:url (url srv "/p1")} :decode :json :reply-to [:pair/done]}]
                    [:rf.http/managed {:request {:url (url srv "/p2")} :decode :json :reply-to [:pair/done]}]]}))
          (rf/dispatch-sync [:pair/go])
          (is (await-latch! done))
          (let [[i1 i2 :as issued] (rows traces :rf.http/issued)
                [h1 h2 :as handled] (managed-handled traces)]
            (is (= 2 (count issued)))
            (is (= 2 (count handled)))
            (is (= [[:rf.work/http [:rf.http/anonymous :pair/go] 1 1] [:rf.work/http [:rf.http/anonymous :pair/go] 2 1]] (mapv work-id issued)))
            (is (< (:id i1) (:id h1) (:id i2) (:id h2))
                "issued(k) precedes handled(k), and pair k precedes pair k+1")
            (is (apply = (map dispatch-id [i1 h1 i2 h2])) "all four rows are in the one issuing run"))))
      (finally (stop-server! srv)))))

;; ===========================================================================
;; G10 — rf2-5g0bt: a NAMED request whose `:request-id` equals an ANONYMOUS
;; request's originating event-id, the two overlapping and completing in
;; REVERSE order. The named `[frame request-id]` and anonymous `[frame
;; event-id]` counters are independent, so both issuances are number 1; the
;; work-id stays exact only because the anonymous logical-id is TAGGED
;; `[:rf.http/anonymous event-id]`. Untagged, both read
;; `[:rf.work/http :audit/go 1 1]` and a join by issuance prefix attributes
;; the late anonymous completion to the named issuance.
;; ===========================================================================

(deftest mixed-named-and-anonymous-requests-carry-distinct-work-ids
  (let [latches {"/a" (CountDownLatch. 1) "/b" (CountDownLatch. 1)}
        got-a   (CountDownLatch. 1)
        got-b   (CountDownLatch. 1)
        replies (atom {})
        srv     (start-server! latches)]
    (try
      (with-traces
        (fn [traces]
          (rf/reg-event :audit/done
            (fn [_ [_ path reply]]
              (swap! replies assoc path (:rf.reply/work-id reply))
              (.countDown ^CountDownLatch (if (= "/a" path) got-a got-b))
              {}))
          (rf/reg-event :audit/go
            (fn [_ [_ path request-id]]
              {:fx [[:rf.http/managed (cond-> {:request  {:url (url srv path)}
                                               :decode   :json
                                               :reply-to [:audit/done path]}
                                        request-id (assoc :request-id request-id))]]}))
          ;; /a anonymous (logical identity: its event-id :audit/go), held;
          ;; /b NAMED with the very same value as its :request-id.
          (rf/dispatch-sync [:audit/go "/a" nil])
          (rf/dispatch-sync [:audit/go "/b" :audit/go])
          ;; the named /b completes FIRST, then the anonymous /a
          (.countDown ^CountDownLatch (latches "/b"))
          (is (await-latch! got-b))
          (.countDown ^CountDownLatch (latches "/a"))
          (is (await-latch! got-a))
          (let [[anon named :as issued] (rows traces :rf.http/issued)
                replied                 (rows traces :rf.http/replied)
                prefix                  #(subvec % 0 3)]
            (is (= 2 (count issued)) "control: two issuances, one issued row each")
            (is (= 2 (count replied)) "control: both requests completed")
            (is (distinct? (work-id anon) (work-id named))
                "a named request-id equal to an anonymous event-id does not collide")
            (is (= [:rf.work/http [:rf.http/anonymous :audit/go] 1 1] (work-id anon))
                "the anonymous logical-id is tagged with its kind")
            (is (= [:rf.work/http :audit/go 1 1] (work-id named))
                "a named request keeps its caller-chosen request-id, untagged")
            (is (= {"/a" (work-id anon) "/b" (work-id named)} @replies)
                "each delivered reply carries its OWN issuance's work-id, whatever the completion order")
            (is (= (set (map (comp prefix work-id) issued))
                   (set (map (comp prefix work-id) replied)))
                "every completion joins exactly one issuance on the three-element prefix"))))
      (finally
        (doseq [l (vals latches)] (.countDown ^CountDownLatch l))
        (stop-server! srv)))))

;; ===========================================================================
;; G11 — rf2-ojn0y: a retried request. The issued row carries the ATTEMPT-1
;; work-id; the completion carries its own attempt. They join on the
;; three-element issuance PREFIX, never on full work-id equality.
;; ===========================================================================

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
          (let [issued  (rows traces :rf.http/issued)
                replied (rows traces :rf.http/replied)
                i-wid   (work-id (first issued))
                r-wid   (work-id (first replied))]
            (is (= 2 @hits) "control: the server really saw two attempts")
            (is (= 1 (count issued)) "one issuance, one issued row, however many attempts")
            (is (= 1 (count replied)))
            (is (= [:rf.work/http :retry/one 1 1] i-wid) "the issued row carries the attempt-1 work-id")
            (is (= [:rf.work/http :retry/one 1 2] r-wid) "the completion carries its own attempt")
            (is (= (subvec i-wid 0 3) (subvec r-wid 0 3))
                "issuance and completion join on the three-element issuance prefix"))))
      (finally (.stop server 0)))))

;; ===========================================================================
;; G7 — a synchronous body-prep failure: the issued row precedes it.
;; ===========================================================================

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
      (let [issued  (rows traces :rf.http/issued)
            failure (first (rows traces :rf.http/transport))]
        (is (= 1 (count issued)))
        (is (some? failure) "control: the prep failure row was emitted")
        (is (< (:id (first issued)) (:id failure)) "the issued row precedes the failure row")
        (is (= (dispatch-id (first issued)) (dispatch-id failure))
            "both rows sit in the issuing bundle")))))

;; ===========================================================================
;; Privacy — `:url` rides redacted under `:sensitive?`.
;; ===========================================================================

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
      (let [tags (:tags (first (rows traces :rf.http/issued)))]
        (is (some? tags))
        (is (true? (:sensitive? (first (rows traces :rf.http/issued))))
            "the row is stamped sensitive")
        (is (not (re-find #"hunter2" (str (:url tags))))
            "the query value is scrubbed from the url")))))

;; ===========================================================================
;; Registry altitude — G5, G6, G8, and the frame-destroy drop.
;; ===========================================================================

(deftest anonymous-counter-is-never-evicted
  (testing "G5 — anonymous a=1, b=2; b completes (evict called exactly as the
  transport calls it, with a nil request-id); the next anonymous issuance
  reads 3, not 1 — a compare-and-drop eviction would have reissued 1 while a
  was still live"
    (rf.http.registry/reset-issuance-counters-for-test!)
    (let [a (rf.http.registry/next-issuance! :frame/f nil [:ev/go])
          b (rf.http.registry/next-issuance! :frame/f nil [:ev/go])]
      (is (= [1 2] [a b]))
      (rf.http.registry/evict-issuance-on-completion! :frame/f nil b)
      (is (= 3 (rf.http.registry/next-issuance! :frame/f nil [:ev/go]))))))

(deftest anonymous-counter-is-per-frame-and-per-event
  (testing "G6 — sibling frames each start at 1 for the same event-id"
    (rf.http.registry/reset-issuance-counters-for-test!)
    (is (= 1 (rf.http.registry/next-issuance! :frame/a nil [:ev/go])))
    (is (= 1 (rf.http.registry/next-issuance! :frame/b nil [:ev/go {:arg 1}])))
    (is (= 2 (rf.http.registry/next-issuance! :frame/a nil [:ev/go])))
    (is (= 1 (rf.http.registry/next-issuance! :frame/a nil [:ev/other]))
        "a different event-id in the same frame runs its own sequence")))

(deftest anonymous-counter-is-bounded-by-frame-x-event
  (testing "G8 — N anonymous issuances of one event hold ONE entry, and never touch the named map"
    (rf.http.registry/reset-issuance-counters-for-test!)
    (dotimes [_ 50] (rf.http.registry/next-issuance! :frame/f nil [:ev/go]))
    (is (= 1 (rf.http.registry/anonymous-issuance-counter-count))
        "one entry per (frame, event-id), not one per request")
    (is (zero? (rf.http.registry/issuance-counter-count))
        "the rf2-k47b3d named-counter leak instrument keeps its meaning")))

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
