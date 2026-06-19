(ns re-frame.http-restore-quiesce-test
  "rf2-u5kmf8 — epoch-restore host-transient quiesce for NON-resource managed
  HTTP. The epoch restore boundary installs the captured durable frame-state
  WHOLESALE, but a plain `:rf.http/managed` request in flight is host work — an
  AbortController / CompletableFuture + an in-flight registry slot, NOT
  frame-state — so the wholesale install leaves it attached to the pre-restore
  timeline. Unlike resource-backed HTTP (whose work-ledger row the resources
  reconcile dangles), a plain managed request has no ledger gate, so its late
  completion would still deliver to its original `:rf/reply-to` target against
  the restored state.

  `abort-in-flight-for-frame!` (published as the `:http/abort-in-flight-for-
  frame!` late-bind hook the epoch boundary fires AFTER a successful install)
  aborts every in-flight managed request the restored frame issued, suppressing
  the app reply (the abort fires with `:reason :epoch-restored`, a reply-
  suppressing reason) and emitting the EP-0011 `:status :stale` /
  `:work/status :suppressed` envelope facts (Managed-Effects §restore: \"epoch
  restore MUST NOT revive host work\").

  Strategy: a blocking in-process server holds the request mid-flight while the
  quiesce fires, so the suppression is observed against a genuinely-in-flight
  request (the actual failing path the bead names), then the server is released
  to prove the late completion delivers nothing to the app target."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as http-registry]
            [re-frame.http.transport :as http-transport]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (require 're-frame.http.managed :reload)
  (http-managed/clear-all-in-flight!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

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
   (test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-restore-quiesce condition"})
   true))

;; ---- hook publication ------------------------------------------------------

(deftest hook-published
  (testing "rf2-u5kmf8 — the :http/abort-in-flight-for-frame! hook is published"
    (is (some? (late-bind/get-fn :http/abort-in-flight-for-frame!)))
    (is (= http-registry/abort-in-flight-for-frame!
           (late-bind/get-fn :http/abort-in-flight-for-frame!)))))

;; ---- registry-level: frame-scoped abort + reason ---------------------------

(deftest abort-in-flight-for-frame-aborts-only-the-frames-requests
  (testing "rf2-u5kmf8 — abort-in-flight-for-frame! fires each matching handle's
            abort-fn with :reason :epoch-restored and leaves other frames alone"
    (http-managed/clear-all-in-flight!)
    (let [seen (atom [])
          mk   (fn [frame-id request-id]
                 (http-registry/seed-in-flight-for-test!
                   request-id nil
                   {:abort-fn (fn [reason] (swap! seen conj [frame-id reason]))
                    :url      "http://x/y"
                    :frame    frame-id}))]
      (mk :frame/restored :req-a)
      (mk :frame/restored :req-b)
      (mk :frame/other    :req-c)
      (http-registry/abort-in-flight-for-frame! :frame/restored)
      (is (= #{[:frame/restored :epoch-restored]}
             (set (map (fn [[f r]] [f r]) @seen)))
          "only the restored frame's handles fired, each with :reason :epoch-restored")
      (is (= 2 (count @seen)) "both of the restored frame's requests were aborted")
      (is (not-any? #(= :frame/other (first %)) @seen)
          "the unrelated frame's request was NOT aborted")
      (http-managed/clear-all-in-flight!))))

(deftest abort-in-flight-for-frame-noop-on-frame-with-no-requests
  (testing "rf2-u5kmf8 — a frame with no in-flight managed HTTP is a clean no-op"
    (http-managed/clear-all-in-flight!)
    (is (nil? (http-registry/abort-in-flight-for-frame! :frame/none)))))

;; ---- end-to-end: suppression of a genuinely in-flight request --------------

(deftest restore-quiesce-suppresses-late-completion-of-in-flight-request
  (testing "rf2-u5kmf8 — a managed request in flight when the frame is restored
            is aborted + its late completion is SUPPRESSED: NO :on-failure /
            :on-success delivery to the original reply target, and an EP-0011
            stale-suppressed trace is emitted"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          replies (atom [])
          traces  (atom [])]
      (try
        (trace/register-listener! ::u5kmf8 (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; an ordinary event handler (no spawned actor) issues a managed
        ;; request with :on-success AND :on-failure recorders — the exact
        ;; non-resource managed-HTTP shape the bead names.
        (rf/reg-event :load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url    (str "http://127.0.0.1:" port "/slow")
                                 :method :get}
                    :decode     :json
                    :request-id :restore/req
                    :on-success [:reply/recorder]
                    :on-failure [:reply/recorder]}]]}))
        (rf/dispatch-sync [:load])
        (await-condition! #(seq (http-managed/in-flight-snapshot)))
        (is (= 1 (count (http-managed/in-flight-snapshot)))
            "precondition: the request is in flight")
        (is (= :rf/default (:frame (http-registry/lookup-in-flight :restore/req)))
            "precondition: the in-flight handle carries its originating frame")
        ;; The restore boundary fires the published hook for the restored frame.
        ((late-bind/get-fn :http/abort-in-flight-for-frame!) :rf/default)
        ;; The abort cascade clears the registry slot.
        (await-condition! #(empty? (http-managed/in-flight-snapshot)))
        ;; Release the server so the late completion (if any) would arrive.
        (.countDown latch)
        ;; Timer-semantics window (rf2-fun38): prove the ABSENCE of any reply
        ;; delivery — there is no positive signal to poll for a non-event.
        (Thread/sleep 100)
        (is (empty? @replies)
            "the late pre-restore completion delivered NOTHING to the reply target")
        (let [stale (filter #(= :rf.http/stale-suppressed (:operation %)) @traces)]
          (is (seq stale)
              "an EP-0011 stale-suppressed trace fired for the suppressed attempt")
          (let [tags (:tags (first stale))]
            (is (= :stale (:rf.reply/status tags))
                "the suppressed attempt's reply status is :stale")
            (is (= :suppressed (:rf.reply/work-status tags))
                "its work-ledger status is :suppressed")
            (is (= :rf/default (:frame tags))
                "the trace names the restored frame")))
        (finally
          (trace/unregister-listener! ::u5kmf8)
          (stop-server! srv))))))
