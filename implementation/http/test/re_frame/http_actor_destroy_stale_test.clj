(ns re-frame.http-actor-destroy-stale-test
  "Per rf2-yrrpe2 + EP-0011 / Managed-Effects §Cancellation: an actor-destroy
  abort whose reply target is OBSOLETE — its event-id names the destroyed
  actor itself (the machine-shape wrapper's `[self-id [:rf.http/failed]]`
  default, or any request whose reply addresses its own actor) — does NOT
  deliver a live `:cancelled`/failure reply to the app target. It lowers to
  the canonical `:status :stale` / `:work/status :suppressed` reply-envelope
  outcome: the app target MUST NOT run, and a `:rf.http/stale-suppressed`
  reply-envelope trace records the carried correlation joined to `:work/id`.

  The contrast (preserved rf2-wvkn behaviour): an actor-destroy abort whose
  reply target is an ORDINARY event (still meaningful — a separate recorder
  handler) keeps the live `:status :cancelled` / `:failure` delivery.

  Spec references:
   - Managed-Effects §Cancellation (`:status :cancelled` when meaningful,
     `:status :stale`/`:suppressed` when teardown made the target obsolete)
   - EP-0011 §Status taxonomy (line 763-768)
   - Spec 014 §Abort on actor destroy

  The obsolescence determinant is STRUCTURAL: when `abort-on-actor-destroy`
  fires, the actor IS under teardown, so a reply addressing that same actor is
  obsolete by construction — no liveness probe (the snapshot is still present
  mid-cascade, since `destroy-single-actor!` aborts in-flight HTTP BEFORE the
  snapshot teardown)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset (mirrors http_actor_destroy_cancellation_test.clj) ----

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http.managed :reload)
  (machines/reset-timers!)
  (http-managed/clear-all-in-flight!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- in-process latch server ----------------------------------------------

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
                                  :label "http-actor-destroy-stale condition"})
   true))

(defn- stale-traces [traces]
  (filter #(= :rf.http/stale-suppressed (:operation %)) traces))

;; ---- (1) obsolete actor-bound target (reply addresses the actor itself) ---
;; ----     → :status :stale / :suppressed, NO app delivery -------------------

(deftest obsolete-actor-bound-target-suppresses-stale-on-destroy
  (testing "rf2-yrrpe2 — an actor whose request's :on-failure addresses the actor itself: destroying it suppresses the app reply as :status :stale (no delivery), and emits a :rf.http/stale-suppressed reply-envelope trace"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          ;; Dispatch probe: a trace listener records EVERY dispatched event's
          ;; id, so we can assert the dead actor's reply event was NEVER
          ;; re-dispatched (there is no live handler to record into otherwise).
          dispatched (atom [])
          traces  (atom [])]
      (try
        (trace/register-listener! ::yrrpe2-1
          (fn [ev]
            (swap! traces conj ev)
            (when (= :rf.event/dispatched (:operation ev))
              (swap! dispatched conj (first (:rf.event/v (:tags ev)))))))
        ;; The child actor issues a managed request whose :on-failure
        ;; addresses ITSELF ([:worker/proc#1 [:self/failed]]) — the
        ;; machine-shape wrapper's `[self-id ...]` shape. After destroy that
        ;; target addresses a now-dead actor → obsolete → suppressed.
        (rf/reg-machine :worker/proc
          {:initial :idle
           :data    {:port port}
           :actions {:fire-request
                     (fn [{data :data}]
                       {:fx [[:rf.http/managed
                              {:request    {:url    (str "http://127.0.0.1:" (:port data) "/slow")
                                            :method :get}
                               :decode     :json
                               :request-id [:worker/proc :slow]
                               ;; reply addressed to the actor itself
                               :on-failure [:worker/proc#1 [:self/failed]]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-request}}})
        (rf/reg-machine :sup/flow
          {:initial :idle
           :states
           {:idle    {:on {:start :working}}
            :working {:spawn {:machine-id :worker/proc
                               :start      [:start]}
                      :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/flow [:start]])
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        (is (contains? (http-managed/actor-in-flight-snapshot) :worker/proc#1))
        ;; Snapshot how many times the actor's own event-id was dispatched
        ;; up to (and including) the spawn/start sequence — the suppressed
        ;; reply must add NO further dispatch to it.
        (let [self-dispatches-before (count (filter #{:worker/proc#1} @dispatched))]
        ;; Destroy the child mid-flight.
        (rf/dispatch-sync [:sup/flow [:cancel]])
        ;; Wait for the canonical stale-suppression trace to land.
        (await-condition! #(seq (stale-traces @traces)))
        (let [tags (:tags (first (stale-traces @traces)))]
          (is (= :stale (:rf.reply/status tags))
              "the obsolete actor-bound completion lowers to :status :stale")
          (is (= :suppressed (:rf.reply/work-status tags)))
          (is (= :rf.http/actor-destroyed-target-obsolete (:rf.reply/stale-reason tags)))
          (is (= :http (:work/kind tags)))
          (is (= [:rf.work/http [:worker/proc :slow] 1 1] (:work/id tags))
              "the canonical join key reads the carried (aborted) attempt's work-id")
          (is (= [:rf.work/http [:worker/proc :slow] 1 1]
                 (:work/id (:rf.reply/carried tags)))))
        ;; Quiescence window: assert the actor's own target was NOT dispatched
        ;; AFTER the destroy (the obsolete app reply MUST NOT run).
        (Thread/sleep 150)
        (is (= self-dispatches-before (count (filter #{:worker/proc#1} @dispatched)))
            "the app reply target (addressing the destroyed actor) MUST NOT be dispatched post-destroy")
        (is (empty? (http-managed/actor-in-flight-snapshot))
            "actor index is empty after the suppressed abort")
        (.countDown latch))
        (finally
          (trace/unregister-listener! ::yrrpe2-1)
          (stop-server! srv))))))

;; ---- (2) meaningful (ordinary-event) target stays a live :cancelled --------
;; ----     failure delivery — the preserved rf2-wvkn behaviour ---------------

(deftest meaningful-ordinary-target-still-delivers-failure-on-destroy
  (testing "rf2-yrrpe2 — an actor whose request's :on-failure is an ORDINARY event (a separate recorder) keeps the live :failure delivery on destroy; NO stale suppression"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          replies (atom [])
          traces  (atom [])]
      (try
        (trace/register-listener! ::yrrpe2-2 (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-machine :worker/proc
          {:initial :idle
           :data    {:port port}
           :actions {:fire-request
                     (fn [{data :data}]
                       {:fx [[:rf.http/managed
                              {:request    {:url    (str "http://127.0.0.1:" (:port data) "/slow")
                                            :method :get}
                               :decode     :json
                               :request-id [:worker/proc :slow]
                               ;; reply addressed to an ORDINARY event
                               :on-failure [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-request}}})
        (rf/reg-machine :sup/flow
          {:initial :idle
           :states
           {:idle    {:on {:start :working}}
            :working {:spawn {:machine-id :worker/proc
                               :start      [:start]}
                      :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/flow [:start]])
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        (rf/dispatch-sync [:sup/flow [:cancel]])
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :cancelled (:status reply))
              "the meaningful target still receives a live :cancelled reply")
          (is (= :rf.http/aborted (get-in reply [:error :kind])))
          (is (= :actor-destroyed (get-in reply [:error :reason]))))
        (is (empty? (stale-traces @traces))
            "a meaningful target emits NO stale-suppression trace")
        (.countDown latch)
        (finally
          (trace/unregister-listener! ::yrrpe2-2)
          (stop-server! srv))))))
