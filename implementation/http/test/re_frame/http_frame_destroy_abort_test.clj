(ns re-frame.http-frame-destroy-abort-test
  "rf2-j538f7.8 — abort frame-owned PLAIN managed HTTP during frame destruction.

  Frame destruction is the hard ownership boundary for SSR per-request frames,
  Story/test variants, hot reload, and multi-frame apps. Every live fetch/future
  and sleeping-backoff handle is frame-stamped, and HTTP already exposes a frame-
  filtered abort walker — but core's `destroy-frame!` cleanup recipe never wired
  plain managed HTTP into that walker (actor teardown reaps only actor-owned
  work; resource teardown reaps only ledger-backed work). An ordinary event-
  handler `:rf.http/managed` request (no actor id — the exposed path) therefore
  survived frame destruction until network completion / timeout / retry
  exhaustion, its late reply routing into an already-destroyed frame.

  The fix publishes `:http/on-frame-destroyed!` (registry/abort-in-flight-on-
  frame-destroyed!) and calls it from `frame/destroy-frame!` AFTER machine +
  resource teardown. It reuses the same frame-filtered, identity-deduped,
  sibling-preserving walk as the epoch-restore quiesce, but fires each abort-fn
  with the reply-suppressing `:reason :frame-destroyed` and stamps the stale-
  suppression trace with `:recovery :suppressed-on-frame-destroy`.

  Strategy: the registry-level tests pin the walker's frame-scoping / reason /
  idempotence against seeded handles; the end-to-end test issues a genuinely
  in-flight blocking request from a named frame, calls the REAL `destroy-frame!`
  (the actual failing path the bead names — before the wiring the registry slot
  survives destroy), and proves the slot clears promptly and the late completion
  delivers nothing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-blocking-server!
  [^CountDownLatch latch status content-type body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex]
                          (.await latch 30 TimeUnit/SECONDS)
                          (let [bs (.getBytes (str body) "UTF-8")]
                            (when content-type
                              (-> ex .getResponseHeaders (.set "Content-Type" content-type)))
                            (try
                              (.sendResponseHeaders ex status (long (count bs)))
                              (with-open [os (.getResponseBody ex)]
                                (.write os bs))
                              (catch Throwable _ nil)))
                          nil))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-frame-destroy condition"})
   true))

;; ---- hook publication (crit 5) --------------------------------------------

(deftest hook-published
  (testing "rf2-j538f7.8 — the :http/on-frame-destroyed! hook is published and
            resolves to the frame-destroy abort walker"
    (is (some? (rf.late-bind/get-fn :http/on-frame-destroyed!)))
    (is (= rf.http.registry/abort-in-flight-on-frame-destroyed!
           (rf.late-bind/get-fn :http/on-frame-destroyed!)))))

;; ---- registry-level: frame-scoped abort + reason (crit 1) -----------------

(deftest frame-destroy-aborts-only-the-frames-requests
  (testing "rf2-j538f7.8 — abort-in-flight-on-frame-destroyed! fires each of the
            destroyed frame's handles exactly once with :reason :frame-destroyed
            and leaves a SIBLING frame's request byte-for-byte live"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])
          ;; production abort-fns clear their own slot via the finalise cascade;
          ;; model that here so the sibling-survival + idempotence checks below
          ;; observe the real post-abort index shape.
          mk   (fn [frame-id request-id]
                 (rf.http.registry/seed-in-flight-for-test!
                   request-id nil
                   {:abort-fn   (fn [reason]
                                  (swap! seen conj [frame-id reason])
                                  (rf.http.registry/clear-in-flight! request-id))
                    :request-id request-id
                    :url        "http://x/y"
                    :frame      frame-id}))]
      (mk :frame/a :req-a1)
      (mk :frame/a :req-a2)
      (mk :frame/b :req-b)
      (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/a)
      (is (= #{[:frame/a :frame-destroyed]} (set @seen))
          "only frame A's handles fired, each with :reason :frame-destroyed")
      (is (= 2 (count @seen)) "both of frame A's requests were aborted, once each")
      (is (nil? (rf.http.registry/lookup-in-flight :req-a1))
          "frame A's first request cleared from the index")
      (is (nil? (rf.http.registry/lookup-in-flight :req-a2))
          "frame A's second request cleared from the index")
      (is (= :frame/b (:frame (rf.http.registry/lookup-in-flight :req-b)))
          "the SIBLING frame's request remains live and untouched")
      (rf.http.managed/clear-all-in-flight!))))

(deftest frame-destroy-noop-on-frame-with-no-requests
  (testing "rf2-j538f7.8 — a frame with no in-flight managed HTTP is a clean no-op"
    (rf.http.managed/clear-all-in-flight!)
    (is (nil? (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/none)))
    (is (nil? (rf.http.registry/abort-in-flight-on-frame-destroyed! nil)))))

;; ---- ordering / idempotence: no duplicate abort on a cleared handle (crit 4)

(deftest frame-destroy-sweep-noop-on-already-cleared-handle
  (testing "rf2-j538f7.8 — when an earlier teardown (actor-destroy / resource
            teardown) already cleared a frame's HTTP slot, the later generic
            frame-destroy sweep produces NO duplicate abort or stale trace"
    (rf.http.managed/clear-all-in-flight!)
    (let [aborts (atom [])
          traces (atom [])]
      (try
        (rf.trace/register-listener! ::idem (fn [ev] (swap! traces conj ev)))
        (rf.http.registry/seed-in-flight-for-test!
          :req/gone nil
          {:abort-fn   (fn [reason] (swap! aborts conj reason))
           :request-id :req/gone
           :url        "http://x/y"
           :frame      :frame/a})
        ;; A more-specific teardown wins first and clears the slot WITHOUT the
        ;; generic sweep's involvement.
        (rf.http.registry/clear-in-flight! :req/gone)
        ;; The generic HTTP frame-destroy sweep then runs — and finds nothing.
        (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/a)
        (is (empty? @aborts)
            "the already-cleared handle is NOT aborted a second time")
        (is (empty? (filter #(= :rf.http/stale-suppressed (:operation %)) @traces))
            "no duplicate stale-suppressed trace for the already-cleared handle")
        (finally
          (rf.trace/unregister-listener! ::idem)
          (rf.http.managed/clear-all-in-flight!))))))

;; ---- end-to-end: destroy-frame! aborts a genuinely in-flight request -------
;; (crit 2 — the adversarial regression: FAILS before the destroy-frame! wiring)

(deftest destroy-frame-aborts-and-suppresses-in-flight-managed-http
  (testing "rf2-j538f7.8 — a plain managed request in flight when its owning
            frame is destroyed is aborted (registry slot clears promptly, before
            the network releases — proving cancellation, not natural completion)
            and its late completion is SUPPRESSED: NO :on-success / :on-failure
            delivery into the destroyed frame, and an EP-0011 stale-suppressed
            trace fires with :recovery :suppressed-on-frame-destroy"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          replies (atom [])
          traces  (atom [])]
      (try
        (rf.trace/register-listener! ::j538f7 (fn [ev] (swap! traces conj ev)))
        (rf/make-frame {:id :frame/req :doc "the frame that owns the in-flight request"})
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; an ordinary event handler (no spawned actor) issues a plain managed
        ;; request from :frame/req — the exact non-actor managed-HTTP shape the
        ;; bead names as the exposed path.
        (rf/reg-event :load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url    (str "http://127.0.0.1:" port "/slow")
                                 :method :get}
                    :decode     :json
                    :request-id :destroy/req
                    :on-success [:reply/recorder]
                    :on-failure [:reply/recorder]}]]}))
        (rf/dispatch-sync [:load] {:frame :frame/req})
        (await-condition! #(seq (rf.http.managed/in-flight-snapshot)))
        (is (= 1 (count (rf.http.managed/in-flight-snapshot)))
            "precondition: the request is in flight")
        (is (= :frame/req (:frame (rf.http.registry/lookup-in-flight :destroy/req)))
            "precondition: the in-flight handle carries its originating frame")
        ;; Destroy the owning frame via the REAL recipe — this is the wiring
        ;; under test (before the fix, destroy-frame! left the slot in flight).
        (rf/destroy-frame! :frame/req)
        ;; The abort cascade clears the registry slot — and it clears while the
        ;; server is STILL BLOCKED, so the clear is the abort, not a natural
        ;; completion.
        (await-condition! #(empty? (rf.http.managed/in-flight-snapshot)))
        (is (empty? (rf.http.managed/in-flight-snapshot))
            "the in-flight slot cleared promptly on frame destroy")
        ;; Release the server so any late completion would arrive.
        (.countDown latch)
        ;; Timer-semantics window (rf2-fun38): prove the ABSENCE of any reply
        ;; delivery — there is no positive signal to poll for a non-event.
        (Thread/sleep 150)
        (is (empty? @replies)
            "the late post-destroy completion delivered NOTHING into the destroyed frame")
        (let [stale (filter #(= :rf.http/stale-suppressed (:operation %)) @traces)
              ev    (first stale)]
          (is (seq stale)
              "an EP-0011 stale-suppressed trace fired for the suppressed attempt")
          ;; Per Spec 009 §Core fields the trace pipeline HOISTS :recovery out of
          ;; :tags to the top-level event slot; the reply facts stay in :tags.
          (is (= :suppressed-on-frame-destroy (:recovery ev))
              "the recovery names frame destroy — NOT epoch restore")
          (let [tags (:tags ev)]
            (is (= :stale (:rf.reply/status tags))
                "the suppressed attempt's reply status is :stale")
            (is (= :suppressed (:rf.reply/work-status tags))
                "its work-ledger status is :suppressed")
            (is (= :frame/req (:frame tags))
                "the trace names the destroyed frame")))
        (finally
          (rf.trace/unregister-listener! ::j538f7)
          (stop-server! srv))))))
