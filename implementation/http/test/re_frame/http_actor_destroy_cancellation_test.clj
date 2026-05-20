(ns re-frame.http-actor-destroy-cancellation-test
  "Per rf2-wvkn — the cross-feature contract that destroying a spawned
  state-machine actor aborts every in-flight `:rf.http/managed` request
  the actor had issued.

  Spec references:
   - Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts
   - Spec 014 §Abort on actor destroy
   - Spec 009 §Error categories — `:rf.http/aborted-on-actor-destroy`

  Test strategy: spin up a tiny in-process HTTP server that blocks on a
  `CountDownLatch` until the test releases it. The state-machine child
  actor issues an `:rf.http/managed` request against that server, the
  parent destroys the child mid-flight, and the test asserts (a) the
  abort handle fired (the request never produced a non-aborted reply),
  (b) the `:rf.http/aborted-on-actor-destroy` trace event fired with
  the right `:actor-id`, and (c) the in-flight registry is clean.

  Coverage matrix (each its own deftest):
   1. :invoke child issues request → parent state exits → request aborts
   2. Multiple in-flight requests from the same actor → all abort
   3. Sibling actors are NOT affected when one is destroyed
   4. Direct event-handler dispatch (no spawned-actor) → no cancellation
   5. Parent state's :after firing destroys the child + aborts its HTTP"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http-managed :as http-managed]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http-managed :reload)
  (machines/reset-timers!)
  (http-managed/clear-all-in-flight!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- in-process latch server ----------------------------------------------

(defn- start-blocking-server!
  "Start a server that blocks on `latch` until released, then writes
  `body` with `status`. Returns `{:server :port}`. Stop with `.stop`."
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
    {:server server
     :port   (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

;; ---- helpers --------------------------------------------------------------

(defn- await-condition!
  "Thin alias over `test-support/poll-until` (rf2-fun38) — preserves the
  per-file arity (`pred`, optional `timeout-ms`)."
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-actor-destroy condition"})
   true))

(defn- abort-traces
  "Filter `traces` for :rf.http/aborted-on-actor-destroy events."
  [traces]
  (filter #(= :rf.http/aborted-on-actor-destroy (:operation %))
          traces))

;; ---- (1) :invoke child issues request → parent state exits → abort -------

(deftest invoke-child-request-aborts-on-parent-state-exit
  (testing "when the parent state exits, the spawned child's in-flight HTTP aborts and emits the documented trace"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          replies (atom [])
          traces  (atom [])]
      (try
        (trace/register-trace-listener! ::wvkn-1 (fn [ev] (swap! traces conj ev)))
        (rf/reg-event-fx :reply/recorder
          (fn [_ [_ payload]]
            (swap! replies conj payload)
            {}))
        ;; Child machine: on entry to :running it dispatches an
        ;; :rf.http/managed request to the slow server. The reply
        ;; lands at the explicit recorder so the test can observe.
        (rf/reg-machine :worker/proc
          {:initial :idle
           :data    {:port port}
           :actions {:fire-request
                     (fn [data _]
                       {:fx [[:rf.http/managed
                              {:request    {:url    (str "http://127.0.0.1:" (:port data) "/slow")
                                            :method :get}
                               :decode     :json
                               :request-id [:worker/proc :slow]
                               :on-failure [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-request}}})
        ;; Parent: :invoke spawns the child, transitions :working ↔ :idle.
        (rf/reg-machine :sup/flow
          {:initial :idle
           :states
           {:idle    {:on {:start :working}}
            :working {:invoke {:machine-id :worker/proc
                               :start      [:start]}
                      :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/flow [:start]])
        ;; Confirm the request is in-flight against the spawned child.
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        (is (= 1 (count (http-managed/actor-in-flight-snapshot)))
            "in-flight registry has one actor entry while the child request is pending")
        (is (contains? (http-managed/actor-in-flight-snapshot) :worker/proc#1)
            "actor index keys on the spawned child's deterministic id")
        ;; Parent destroys the child by transitioning out.
        (rf/dispatch-sync [:sup/flow [:cancel]])
        ;; The abort dispatches a :failure reply through :on-failure.
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :failure (:kind reply))
              "the abort surfaces as a :failure reply on :on-failure")
          (is (= :rf.http/aborted (get-in reply [:failure :kind])))
          (is (= :actor-destroyed (get-in reply [:failure :reason]))
              "the :reason discriminates actor-destroy from user-abort"))
        (let [trace-evs (abort-traces @traces)]
          (is (seq trace-evs)
              ":rf.http/aborted-on-actor-destroy trace event fired")
          (let [tags (:tags (first trace-evs))]
            (is (= :worker/proc#1 (:actor-id tags))
                "trace tags carry the destroyed spawned-actor id")
            (is (= [:worker/proc :slow] (:request-id tags))
                "trace tags carry the user-supplied :request-id")))
        (is (empty? (http-managed/actor-in-flight-snapshot))
            "actor index is empty after the abort")
        (.countDown latch)
        (finally
          (trace/unregister-trace-listener! ::wvkn-1)
          (stop-server! srv))))))

;; ---- (2) multiple in-flight requests from one actor → all abort ----------

(deftest multiple-in-flight-from-one-actor-all-abort
  (testing "when an actor has multiple in-flight HTTP requests, destroying it aborts every one"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{}")
          replies (atom [])
          traces  (atom [])]
      (try
        (trace/register-trace-listener! ::wvkn-2 (fn [ev] (swap! traces conj ev)))
        (rf/reg-event-fx :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-machine :worker/multi
          {:initial :idle
           :data    {:port port}
           :actions {:fire-three
                     (fn [data _]
                       {:fx [[:rf.http/managed
                              {:request    {:url (str "http://127.0.0.1:" (:port data) "/a")}
                               :decode     :json
                               :request-id :a
                               :on-failure [:reply/recorder]}]
                             [:rf.http/managed
                              {:request    {:url (str "http://127.0.0.1:" (:port data) "/b")}
                               :decode     :json
                               :request-id :b
                               :on-failure [:reply/recorder]}]
                             [:rf.http/managed
                              {:request    {:url (str "http://127.0.0.1:" (:port data) "/c")}
                               :decode     :json
                               :request-id :c
                               :on-failure [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-three}}})
        (rf/reg-machine :sup/multi
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:invoke {:machine-id :worker/multi
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/multi [:start]])
        ;; Wait for all three in-flight against the same actor.
        (await-condition!
          #(let [snap (http-managed/actor-in-flight-snapshot)]
             (and (= 1 (count snap))
                  (= 3 (count (val (first snap)))))))
        ;; Destroy.
        (rf/dispatch-sync [:sup/multi [:cancel]])
        (await-condition! #(= 3 (count @replies)))
        (is (every? #(= :failure (:kind %)) @replies))
        (is (every? #(= :actor-destroyed (get-in % [:failure :reason])) @replies))
        (is (= 3 (count (abort-traces @traces)))
            "three :rf.http/aborted-on-actor-destroy traces — one per cancelled request")
        (is (empty? (http-managed/actor-in-flight-snapshot)))
        (.countDown latch)
        (finally
          (trace/unregister-trace-listener! ::wvkn-2)
          (stop-server! srv))))))

;; ---- (3) sibling actors are NOT affected ----------------------------------

(deftest sibling-actors-not-affected-by-destroy
  (testing "destroying actor A does not abort actor B's in-flight requests — actor-id scoping is structural"
    (let [latch-a (CountDownLatch. 1)
          latch-b (CountDownLatch. 1)
          srv-a   (start-blocking-server! latch-a 200 "application/json" "{}")
          srv-b   (start-blocking-server! latch-b 200 "application/json" "{}")
          replies (atom [])]
      (try
        (rf/reg-event-fx :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; Two independent worker machines, each with its own request.
        (rf/reg-machine :worker/proc-a
          {:initial :idle
           :data    {:port (:port srv-a)}
           :actions {:fire (fn [data _]
                             {:fx [[:rf.http/managed
                                    {:request    {:url (str "http://127.0.0.1:" (:port data) "/")}
                                     :decode     :json
                                     :request-id :a
                                     :on-failure [:reply/recorder]
                                     :on-success [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire}}})
        (rf/reg-machine :worker/proc-b
          {:initial :idle
           :data    {:port (:port srv-b)}
           :actions {:fire (fn [data _]
                             {:fx [[:rf.http/managed
                                    {:request    {:url (str "http://127.0.0.1:" (:port data) "/")}
                                     :decode     :json
                                     :request-id :b
                                     :on-failure [:reply/recorder]
                                     :on-success [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire}}})
        ;; Two top-level parents — each spawns one worker.
        (rf/reg-machine :sup/a
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:invoke {:machine-id :worker/proc-a
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/reg-machine :sup/b
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:invoke {:machine-id :worker/proc-b
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/a [:start]])
        (rf/dispatch-sync [:sup/b [:start]])
        (await-condition! #(= 2 (count (http-managed/actor-in-flight-snapshot))))
        ;; Destroy A only.
        (rf/dispatch-sync [:sup/a [:cancel]])
        (await-condition! #(seq @replies))
        (is (= 1 (count @replies))
            "exactly one reply — A's. B is still pending")
        (is (= :actor-destroyed (get-in (first @replies) [:failure :reason])))
        (is (= 1 (count (http-managed/actor-in-flight-snapshot)))
            "B remains in the in-flight registry")
        ;; Now destroy B.
        (rf/dispatch-sync [:sup/b [:cancel]])
        (await-condition! #(= 2 (count @replies)))
        (is (every? #(= :actor-destroyed (get-in % [:failure :reason])) @replies))
        (is (empty? (http-managed/actor-in-flight-snapshot)))
        (.countDown latch-a)
        (.countDown latch-b)
        (finally
          (stop-server! srv-a)
          (stop-server! srv-b))))))

;; ---- (4) direct event-handler dispatch — no cancellation -----------------

(deftest direct-handler-dispatch-not-subject-to-actor-cancellation
  (testing "a request dispatched from an ordinary event handler (no spawned-actor envelope) is NOT subject to actor-destroy cancellation"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{}")
          replies (atom [])]
      (try
        (rf/reg-event-fx :direct/load
          (fn [_ [_ msg]]
            (if-let [reply (:rf/reply msg)]
              (do (swap! replies conj reply) {})
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/")}
                      :decode     :json
                      :request-id :direct}]]})))
        (rf/dispatch-sync [:direct/load {}])
        (await-condition! #(seq (http-managed/in-flight-snapshot)))
        (is (empty? (http-managed/actor-in-flight-snapshot))
            "direct event-handler dispatch is not tracked under actor-in-flight")
        (is (= 1 (count (http-managed/in-flight-snapshot)))
            "request-id index does record the request")
        ;; Calling abort-on-actor-destroy with any actor-id is a no-op
        ;; for this request — there's no actor binding.
        (http-managed/abort-on-actor-destroy :random/non-existent-actor-id)
        ;; Timer-semantics sleep (rf2-fun38): proving the *absence* of any
        ;; reply — no observable signal to poll. The 50ms window confirms
        ;; no stray dispatch surfaces from the no-op abort path.
        (Thread/sleep 50)
        (is (empty? @replies)
            "abort-on-actor-destroy is structurally scoped — it does not touch direct-dispatch requests")
        (is (= 1 (count (http-managed/in-flight-snapshot)))
            "request still in flight")
        ;; The orthogonal app-level abort still works — driven through
        ;; an event handler that emits the `:rf.http/managed-abort` fx.
        (rf/reg-event-fx :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :direct]]}))
        (rf/dispatch-sync [:do/abort])
        (await-condition! #(seq @replies))
        (is (= :failure (:kind (first @replies))))
        (is (= :rf.http/aborted (get-in (first @replies) [:failure :kind])))
        (is (= :user (get-in (first @replies) [:failure :reason]))
            "manual abort produces :reason :user (not :actor-destroyed)")
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- (5) parent state's :after firing destroys the child + aborts HTTP ---

(deftest after-firing-cascades-to-http-abort
  (testing ":after firing on the parent state destroys the spawned child AND aborts its HTTP — rf2-3y3y composes with rf2-wvkn"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{}")
          replies (atom [])]
      (try
        (rf/reg-event-fx :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-machine :worker/slow
          {:initial :idle
           :data    {:port port}
           :actions {:fire (fn [data _]
                             {:fx [[:rf.http/managed
                                    {:request    {:url (str "http://127.0.0.1:" (:port data) "/")}
                                     :decode     :json
                                     :request-id :slow
                                     :on-failure [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire}}})
        ;; Parent has :after — but JVM tests fire `:after` via the
        ;; synthetic timer event (mirrors the pattern in
        ;; machines_cljs_test.cljs §machine-after-cljs).
        (rf/reg-machine :sup/timed
          {:initial :idle
           :data    {:rf/after-epoch 0}
           :states
           {:idle    {:on {:start :working}}
            :working {:invoke {:machine-id :worker/slow
                               :start      [:start]}
                      :after  {5000 :timeout}}
            :timeout {}}})
        (rf/dispatch-sync [:sup/timed [:start]])
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        ;; Synthetically fire the :after timer with matching epoch (1
        ;; after the entry to :working). This drives the parent's
        ;; transition out of :working — the standard exit cascade
        ;; destroys the spawned :worker/slow#1 and the rf2-wvkn hook
        ;; aborts its in-flight HTTP.
        (let [snap  (get-in (rf/get-frame-db :rf/default) [:rf/machines :sup/timed])
              epoch (get-in snap [:data :rf/after-epoch])]
          (rf/dispatch-sync [:sup/timed [:rf.machine.timer/after-elapsed 5000 epoch]]))
        (await-condition! #(seq @replies))
        (is (= :failure (:kind (first @replies))))
        (is (= :actor-destroyed (get-in (first @replies) [:failure :reason]))
            ":after-driven destroy cascades to the same :reason :actor-destroyed")
        (is (empty? (http-managed/actor-in-flight-snapshot)))
        (.countDown latch)
        (finally (stop-server! srv))))))
