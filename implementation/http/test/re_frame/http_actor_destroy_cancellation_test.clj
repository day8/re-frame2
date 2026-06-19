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
   1. :spawn child issues request → parent state exits → request aborts
   2. Multiple in-flight requests from the same actor → all abort
   3. Sibling actors are NOT affected when one is destroyed
   4. Direct event-handler dispatch (no spawned-actor) → no cancellation
   5. Parent state's :after firing destroys the child + aborts its HTTP
   6. Anonymous (request-id-less) child request → actor-destroy abort
      cleans the actor-in-flight index (rf2-lz7se)
   6b. Registry-level: 2-arg clear-in-flight! empties an anonymous
       handle's actor slot without a pre-clear (the unconditional-
       correctness guarantee); 1-arg form leaks it (rf2-lz7se)
   6c. `schedule-backoff-handle!`'s abort-fn cleans an anonymous
       (request-id-less) backoff handle's actor slot when fired by a
       trigger that does NOT pre-clear the actor slot — the sibling of
       (6b) at the second abort-fn site (rf2-meq28)
   7.  IMPERATIVELY-spawned actor (`[:rf.machine/spawn …]` from an
       ordinary event handler — NO `:spawned` registry slot) → its managed
       request is aborted on imperative `[:rf.machine/destroy …]`. This is
       the rf2-n877mb widening test: step 1's registry-membership
       `owning-actor-id` classified such a request as unowned (the actor is
       absent from `[:rf.runtime/machines :spawned]`); step 2 switches
       ownership to the durable snapshot `:rf/machine-type` marker, so
       imperative spawns are now owners. Tests 1–6c cover declarative
       `:spawn` only and cannot catch this widening (rf2-n877mb)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as http-registry]
            [re-frame.http.transport :as http-transport]
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
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP / machine / routing fxs now require a carried
  ;; frame stamp. This suite exercises the ambient dispatch path against
  ;; a single conventional app frame, so register `:rf/default` explicitly
  ;; and pin it as the established scope for the whole body via with-frame.
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

;; ---- (1) :spawn child issues request → parent state exits → abort -------

(deftest invoke-child-request-aborts-on-parent-state-exit
  (testing "when the parent state exits, the spawned child's in-flight HTTP aborts and emits the documented trace"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          replies (atom [])
          traces  (atom [])]
      (try
        (trace/register-listener! ::wvkn-1 (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
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
                     (fn [{data :data}]
                       {:fx [[:rf.http/managed
                              {:request    {:url    (str "http://127.0.0.1:" (:port data) "/slow")
                                            :method :get}
                               :decode     :json
                               :request-id [:worker/proc :slow]
                               :on-failure [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-request}}})
        ;; Parent: :spawn spawns the child, transitions :working ↔ :idle.
        (rf/reg-machine :sup/flow
          {:initial :idle
           :states
           {:idle    {:on {:start :working}}
            :working {:spawn {:machine-id :worker/proc
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
          (trace/unregister-listener! ::wvkn-1)
          (stop-server! srv))))))

;; ---- (2) multiple in-flight requests from one actor → all abort ----------

(deftest multiple-in-flight-from-one-actor-all-abort
  (testing "when an actor has multiple in-flight HTTP requests, destroying it aborts every one"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{}")
          replies (atom [])
          traces  (atom [])]
      (try
        (trace/register-listener! ::wvkn-2 (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-machine :worker/multi
          {:initial :idle
           :data    {:port port}
           :actions {:fire-three
                     (fn [{data :data}]
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
                     :working {:spawn {:machine-id :worker/multi
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
          (trace/unregister-listener! ::wvkn-2)
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
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; Two independent worker machines, each with its own request.
        (rf/reg-machine :worker/proc-a
          {:initial :idle
           :data    {:port (:port srv-a)}
           :actions {:fire (fn [{data :data}]
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
           :actions {:fire (fn [{data :data}]
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
                     :working {:spawn {:machine-id :worker/proc-a
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/reg-machine :sup/b
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:spawn {:machine-id :worker/proc-b
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
        (rf/reg-event :direct/load
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
        (rf/reg-event :do/abort
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
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-machine :worker/slow
          {:initial :idle
           :data    {:port port}
           :actions {:fire (fn [{data :data}]
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
           :data    {}
           :states
           {:idle    {:on {:start :working}}
            :working {:spawn {:machine-id :worker/slow
                               :start      [:start]}
                      :after  {5000 :timeout}}
            :timeout {}}})
        (rf/dispatch-sync [:sup/timed [:start]])
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        ;; Synthetically fire the :after timer with matching epoch (the
        ;; :working node's per-path epoch, 1 after entry) + decl-path. This
        ;; drives the parent's transition out of :working — the standard
        ;; exit cascade destroys the spawned :worker/slow#1 and the
        ;; rf2-wvkn hook aborts its in-flight HTTP.
        (let [snap  (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/machines :snapshots :sup/timed])
              epoch (get-in snap [:data :rf/after-epoch [:working]])]
          (rf/dispatch-sync [:sup/timed [:rf.machine.timer/after-elapsed 5000 epoch [:working]]]))
        (await-condition! #(seq @replies))
        (is (= :failure (:kind (first @replies))))
        (is (= :actor-destroyed (get-in (first @replies) [:failure :reason]))
            ":after-driven destroy cascades to the same :reason :actor-destroyed")
        (is (empty? (http-managed/actor-in-flight-snapshot)))
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- (6) anonymous (request-id-less) child request → actor-destroy clean --

(deftest anonymous-child-request-abort-cleans-actor-index
  (testing "an anonymous (no :request-id) request issued from inside a spawned actor is indexed ONLY in actor-in-flight; actor-destroy aborts it and the abort-fn's cleanup leaves the actor index empty (rf2-lz7se — the abort-fn passes its in-scope handle to clear-in-flight!, so cleanup is unconditionally correct rather than depending on the actor-destroy eager-dissoc invariant)"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; Child machine: issues a managed request with NO :request-id.
        ;; record-in-flight! therefore skips the request-id index (the
        ;; `(when request-id ...)` guard) and indexes the handle ONLY
        ;; under actor-in-flight, keyed on the spawned child's id.
        (rf/reg-machine :worker/anon
          {:initial :idle
           :data    {:port port}
           :actions {:fire-anon
                     (fn [{data :data}]
                       {:fx [[:rf.http/managed
                              {:request    {:url    (str "http://127.0.0.1:" (:port data) "/slow")
                                            :method :get}
                               :decode     :json
                               ;; deliberately NO :request-id — anonymous.
                               :on-failure [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-anon}}})
        (rf/reg-machine :sup/anon
          {:initial :idle
           :states
           {:idle    {:on {:start :working}}
            :working {:spawn {:machine-id :worker/anon
                               :start      [:start]}
                      :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/anon [:start]])
        ;; The anonymous request lands ONLY in the actor index.
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        (is (= 1 (count (http-managed/actor-in-flight-snapshot)))
            "actor index holds the anonymous request under the spawned child's id")
        (is (contains? (http-managed/actor-in-flight-snapshot) :worker/anon#1))
        (is (empty? (http-managed/in-flight-snapshot))
            "anonymous request is NOT in the request-id index (request-id is nil)")
        ;; Sanity: the handle has no :request-id, confirming the abort-fn's
        ;; 1-arg clear-in-flight! would have no-op'd. The 2-arg form (the fix)
        ;; cleans by handle identity regardless.
        (is (nil? (:request-id (first (val (first (http-managed/actor-in-flight-snapshot))))))
            "the in-flight handle carries no :request-id — the leak vector the 1-arg form left open")
        ;; Parent destroys the child → abort-on-actor-destroy fires each
        ;; handle's abort-fn, which now passes the handle to clear-in-flight!.
        (rf/dispatch-sync [:sup/anon [:cancel]])
        (await-condition! #(seq @replies))
        (is (= :failure (:kind (first @replies)))
            "the anonymous request's abort surfaces as a :failure reply")
        (is (= :rf.http/aborted (get-in (first @replies) [:failure :kind])))
        (is (= :actor-destroyed (get-in (first @replies) [:failure :reason])))
        (is (empty? (http-managed/actor-in-flight-snapshot))
            "actor-in-flight index is empty after the abort — the abort-fn's handle-passing cleanup left no stale slot")
        (is (empty? (http-managed/in-flight-snapshot))
            "request-id index remains empty")
        (.countDown latch)
        (finally (stop-server! srv))))))

;; ---- (6b) registry-level: 2-arg cleanup of an anonymous handle is the -----
;; ----      load-bearing unconditional-correctness guarantee (rf2-lz7se) ----

(deftest anonymous-handle-cleared-without-actor-slot-preclear
  (testing "clearing an anonymous (request-id-less) handle by identity empties the actor-in-flight slot even when the slot is NOT pre-cleared first — this is the defensive guarantee the abort-fn now relies on by passing its in-scope handle. The earlier 1-arg form resolved by request-id and no-op'd on nil, leaking the slot under any abort trigger that does not pre-clear (the actor-destroy eager dissoc was the only thing masking this)"
    (http-managed/clear-all-in-flight!)
    (let [actor-id :worker/anon#7
          ;; Anonymous: request-id nil, actor-id set. record-in-flight!
          ;; stamps :actor-id and pushes the handle into actor-in-flight
          ;; only (the request-id index is skipped on nil id).
          handle   (http-registry/record-in-flight!
                     nil actor-id {:abort-fn (fn [_] nil) :url "http://x/anon"})]
      (is (empty? (http-managed/in-flight-snapshot))
          "anonymous handle is absent from the request-id index")
      (is (= [handle] (get (http-managed/actor-in-flight-snapshot) actor-id))
          "anonymous handle lives solely in the actor-in-flight index, identity-equal")
      ;; Clear via the 2-arg form WITHOUT touching the actor slot first —
      ;; this simulates a future abort trigger (e.g. a frame-level abort-all
      ;; or a timeout-driven abort) that does NOT pre-clear actor-in-flight.
      ;; The 2-arg form's identity-based remove-from-actor-index! empties it.
      (http-registry/clear-in-flight! nil handle)
      (is (empty? (http-managed/actor-in-flight-snapshot))
          "2-arg clear-in-flight! removed the anonymous handle from the actor index by identity")
      ;; Contrast: the 1-arg form is a full no-op on a nil request-id — it
      ;; cannot reach the actor index for an anonymous handle. Re-record and
      ;; prove the leak the fix closes.
      (let [h2 (http-registry/record-in-flight!
                 nil actor-id {:abort-fn (fn [_] nil) :url "http://x/anon2"})]
        (http-registry/clear-in-flight! nil) ; 1-arg, nil id → no-op
        (is (= [h2] (get (http-managed/actor-in-flight-snapshot) actor-id))
            "1-arg clear-in-flight! leaves the anonymous handle stranded — the latent leak the 2-arg fix eliminates")
        ;; Clean up via the correct form so the fixture leaves a clean registry.
        (http-registry/clear-in-flight! nil h2)
        (is (empty? (http-managed/actor-in-flight-snapshot)))))))

;; ---- (6c) the SECOND abort-fn site — schedule-backoff-handle! (rf2-meq28) ---
;; ----      sibling of (6b): a backoff-window abort fired WITHOUT a -----------
;; ----      pre-clear must clean the anonymous handle's actor slot -----------

(def ^:private schedule-backoff-handle!
  @#'http-transport/schedule-backoff-handle!)

(deftest backoff-abort-fn-cleans-anonymous-handle-without-actor-slot-preclear
  (testing "schedule-backoff-handle!'s abort-fn — the SECOND of two structurally-identical abort-fns — cleans an anonymous (request-id-less, issued-from-actor) backoff handle's actor-in-flight slot when fired by a trigger that does NOT pre-clear the slot first. This is the rf2-meq28 sibling of (6b): the abort-fn now passes its in-scope handle to the 2-arg clear-in-flight!, so the actor slot is removed by identity regardless of the nil request-id. The earlier 1-arg form no-op'd on the nil id and stranded the handle under any abort trigger that does not pre-clear (actor-destroy's eager dissoc was the only thing masking the leak)"
    (http-managed/clear-all-in-flight!)
    (let [actor-id :worker/anon-backoff#1
          ;; Anonymous request sitting in a backoff window: request-id nil,
          ;; actor-id set. A request issued from inside a spawned actor with
          ;; a `:retry` config and no `:request-id` lands here. The ctx
          ;; silences its reply via explicit `:on-failure nil` so the
          ;; abort-fn's `dispatch-aborted!` reply-dispatch is a clean no-op,
          ;; isolating this test on the registry teardown.
          ctx      {:request-id          nil
                    :actor-id            actor-id
                    :url                 "http://x/anon-backoff"
                    :sensitive?          false
                    :explicit-on-failure {:supplied? true :value nil}}
          ;; A very long delay so the retry timer never fires during the
          ;; test — the abort-fn wins the once-only `fired?` CAS and the
          ;; timer callback (which would otherwise also reach the registry)
          ;; bails on its lost CAS.
          _        (schedule-backoff-handle! ctx 600000)
          slot     (get (http-managed/actor-in-flight-snapshot) actor-id)
          handle   (first slot)]
      (is (= 1 (count slot))
          "the anonymous backoff handle is registered solely in the actor-in-flight index")
      (is (empty? (http-managed/in-flight-snapshot))
          "anonymous backoff handle is absent from the request-id index (request-id is nil)")
      (is (nil? (:request-id handle))
          "the registered backoff handle carries no :request-id — the leak vector the 1-arg form left open")
      ;; Fire the abort-fn DIRECTLY (the abort trigger) WITHOUT touching the
      ;; actor slot first — this simulates a future non-pre-clearing trigger
      ;; (frame-level abort-all, a timeout-driven abort of a sleeping retry).
      ;; Before rf2-meq28 the abort-fn's 1-arg clear-in-flight! no-op'd on the
      ;; nil id and the handle stranded here; the 2-arg form (the fix) removes
      ;; it from the actor index by identity.
      ((:abort-fn handle) :actor-destroyed)
      (is (empty? (http-managed/actor-in-flight-snapshot))
          "the backoff abort-fn's handle-passing 2-arg clear-in-flight! removed the anonymous handle from the actor index — no stranded slot")
      (is (empty? (http-managed/in-flight-snapshot))
          "request-id index remains empty"))))

;; ---- (7) IMPERATIVELY-spawned actor (rf2-n877mb) → managed HTTP aborts ----
;; ----     on imperative destroy. This is the widening test the registry- ---
;; ----     membership `owning-actor-id` (step 1) could NOT pass: an ---------
;; ----     imperative `[:rf.machine/spawn …]` from an ordinary event handler -
;; ----     installs a snapshot WITHOUT a `[:rf.runtime/machines :spawned …]` -
;; ----     registry slot (that slot is gated on the declarative-desugar ------
;; ----     `:rf/parent-id` + `:rf/invoke-id`), so the step-1 registry read ----
;; ----     classified the actor's request as unowned and never aborted it. ---
;; ----     Step 2 switches ownership to the durable snapshot `:rf/machine- ---
;; ----     type` marker (the SAME discriminator the destroy side keys on), ---
;; ----     widening the owning set to imperative spawns. The existing net ----
;; ----     above (tests 1–6c) covers DECLARATIVE `:spawn` only, so it cannot -
;; ----     catch this widening — hence this dedicated case.

(deftest imperatively-spawned-actor-request-aborts-on-imperative-destroy
  (testing "a managed :rf.http/managed request issued from an IMPERATIVELY-spawned actor (no :spawned registry slot) is aborted when the actor is imperatively destroyed — rf2-n877mb widens owning-actor-id to the snapshot :rf/machine-type marker"
    (let [latch  (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch 200 "application/json" "{\"too\":\"late\"}")
          replies (atom [])
          traces  (atom [])]
      (try
        (trace/register-listener! ::n877mb (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; Worker machine: on entry to :running its action fires an
        ;; :rf.http/managed request at the slow server. Spawned IMPERATIVELY
        ;; below via a hand-emitted [:rf.machine/spawn …] fx (NOT a
        ;; declarative state-node :spawn) — so it gets a snapshot stamped
        ;; with :rf/machine-type but NO [:rf.runtime/machines :spawned …]
        ;; registry slot.
        (rf/reg-machine :worker/imp
          {:initial :idle
           :data    {:port port}
           :actions {:fire-request
                     (fn [{data :data}]
                       {:fx [[:rf.http/managed
                              {:request    {:url    (str "http://127.0.0.1:" (:port data) "/slow")
                                            :method :get}
                               :decode     :json
                               :request-id [:worker/imp :slow]
                               :on-failure [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire-request}}})
        ;; Ordinary event handler emits the IMPERATIVE spawn fx. No parent
        ;; machine, no declarative :spawn desugar — the canonical
        ;; XState-`spawn`-equivalent imperative entry-point. The actor's
        ;; deterministic id is :worker/imp#1 (runtime-db spawn-counter
        ;; fallback). The :start event drives idle→running → :fire-request.
        (rf/reg-event :imp/spawn
          (fn [_ _]
            {:fx [[:rf.machine/spawn {:machine-id :worker/imp
                                      :id-prefix  :worker/imp
                                      :start      [:start]}]]}))
        ;; Ordinary event handler emits the IMPERATIVE destroy fx — the
        ;; canonical [:rf.machine/destroy <actor-id>] keyword form (re-frame2's
        ;; stopChild). This is the destroy trigger that must cascade to the
        ;; HTTP abort.
        (rf/reg-event :imp/destroy
          (fn [_ _]
            {:fx [[:rf.machine/destroy :worker/imp#1]]}))
        (rf/dispatch-sync [:imp/spawn])
        ;; Precondition: the imperatively-spawned actor's snapshot is live,
        ;; carries the :rf/machine-type marker, and is ABSENT from the
        ;; :spawned registry — the exact shape step 1 could not classify.
        (let [rt (rf/runtime-db-value :rf/default)]
          (is (some? (get-in rt [:rf.runtime/machines :snapshots :worker/imp#1 :rf/machine-type]))
              "imperatively-spawned actor's snapshot carries the :rf/machine-type marker")
          (is (nil? (get-in rt [:rf.runtime/machines :spawned]))
              "imperative spawn installs NO :spawned registry slot — the step-1 registry read would have classified its request as unowned"))
        ;; The request is in-flight, indexed under the actor's id — proof that
        ;; the widened owning-actor-id classified the imperative actor as owner.
        (await-condition! #(seq (http-managed/actor-in-flight-snapshot)))
        (is (= 1 (count (http-managed/actor-in-flight-snapshot)))
            "in-flight registry has one actor entry while the imperative actor's request is pending")
        (is (contains? (http-managed/actor-in-flight-snapshot) :worker/imp#1)
            "actor index keys on the imperatively-spawned actor's id — the widening this test pins")
        ;; Imperatively destroy the actor mid-flight.
        (rf/dispatch-sync [:imp/destroy])
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :failure (:kind reply))
              "the abort surfaces as a :failure reply on :on-failure")
          (is (= :rf.http/aborted (get-in reply [:failure :kind])))
          (is (= :actor-destroyed (get-in reply [:failure :reason]))
              "the :reason discriminates actor-destroy from user-abort"))
        (let [trace-evs (abort-traces @traces)]
          (is (seq trace-evs)
              ":rf.http/aborted-on-actor-destroy trace event fired for the imperative actor")
          (let [tags (:tags (first trace-evs))]
            (is (= :worker/imp#1 (:actor-id tags))
                "trace tags carry the destroyed imperatively-spawned actor id")
            (is (= [:worker/imp :slow] (:request-id tags))
                "trace tags carry the user-supplied :request-id")))
        (is (empty? (http-managed/actor-in-flight-snapshot))
            "actor index is empty after the imperative-destroy abort")
        (.countDown latch)
        (finally
          (trace/unregister-listener! ::n877mb)
          (stop-server! srv))))))
