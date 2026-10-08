(ns re-frame.frame-upsert-linearization-jvm-test
  "Frame-id construction linearizes (JVM only; CLJS is single-threaded). The
  construction transaction reserves the id before adapter callbacks and keeps it
  through publication or exact rollback, so a re-registration racing a destroy
  cannot resurrect a partial zombie record, a same-id contender cannot publish
  its config or trace policy, and a failed re-registration restores the prior
  record without erasing concurrent work. `:rf.frame/must-create?` throws
  `:rf.error/frame-id-taken` on a live id.

  A re-registering live frame stays visible to every actor with its staged
  config. These tests read the dev-only trace retention ring, so the prod gate
  skips this namespace; `frame-upsert-linearization-production-test` re-proves
  the contention invariants through production state. Windows open on the
  `rf.frame/*upsert-decide-probe*` and `*upsert-policy-probe*` seams."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            ;; Loads the trace-tooling artefact so its retention-policy
            ;; late-bind is published before these tests run (the retention
            ;; override is otherwise a silent no-op) and so the per-frame
            ;; retention store is observable.
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  ;; the trace-policy stores live outside `frames`
  (rf.trace/clear-frame-no-emit!)
  (rf.trace.tooling/clear-trace-rings!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

;; the frame's retention cap in the trace-tooling ring store, or nil
(defn- retained-cap [frame-id]
  (get-in @@#'re-frame.trace.tooling/trace-rings [frame-id :events-retained]))

(use-fixtures :each reset-runtime)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- window-probe [target reached release]
  (fn [id]
    (when (= id target)
      (.countDown ^CountDownLatch reached)
      (.await ^CountDownLatch release 10 TimeUnit/SECONDS))))

(deftest reregister-owner-rejects-concurrent-destroy-no-zombie
  (rf.frame/upsert-frame! :zombie/x {:tags #{:orig}})
  (let [token-orig (rf.frame/frame-incarnation-token :zombie/x)
        reached    (CountDownLatch. 1)
        release    (CountDownLatch. 1)
        a (binding [rf.frame/*upsert-decide-probe* (window-probe :zombie/x reached release)]
            (future (rf.frame/upsert-frame! :zombie/x {:tags #{:reregister}})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A owns the re-registration transaction")
    (is (nil? (rf.frame/destroy-frame! :zombie/x))
        "same-id destroy loses promptly without disturbing the transaction")
    (.countDown release)
    ;; the original incarnation survives as a full record with the owner's config
    (is (= [:zombie/x true #{:reregister} true]
           [@a
            (identical? token-orig (rf.frame/frame-incarnation-token :zombie/x))
            (get-in (rf.frame/frame :zombie/x) [:config :tags])
            (some? (rf.frame/frame-state-container :zombie/x))]))))

(deftest must-create-throws-typed-collision-on-an-already-live-id
  (rf.frame/upsert-frame! :mc/taken {})
  (let [container (rf.frame/frame-state-container :mc/taken)]
    (is (= [:rf.error/frame-id-taken true]
           [(err-id #(rf.frame/upsert-frame! :mc/taken {:rf.frame/must-create? true}))
            (identical? container (rf.frame/frame-state-container :mc/taken))])
        "the live frame is neither adopted nor refreshed")))

(deftest must-create-installs-cleanly-on-a-free-id
  (is (= [:mc/free true false]
         [(rf.frame/upsert-frame! :mc/free {:rf.frame/must-create? true})
          (some? (rf.frame/frame-state-container :mc/free))
          (contains? (:config (rf.frame/frame :mc/free)) :rf.frame/must-create?)])
      "a full record, with the construction-only key stripped from its config"))

(deftest exclusive-create-loser-must-not-overwrite-winner-trace-policy
  ;; the retention ring arm; the no-emit store is the production twin's
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a       (binding [rf.frame/*upsert-decide-probe*
                          (window-probe :tp/race reached release)]
                  (future
                    (rf.frame/upsert-frame! :tp/race
                                         {:rf.frame/must-create? true
                                          :rf.trace/frame-no-emit? false
                                          :rf.trace/events-retained 10})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A owns the id before policy publication")
    (is (= [:rf.error/frame-construction-in-progress nil]
           [(err-id #(rf.frame/upsert-frame! :tp/race
                                             {:rf.trace/frame-no-emit? true
                                              :rf.trace/events-retained 99}))
            (retained-cap :tp/race)])
        "B loses, and no retention policy is published while A is paused")
    (.countDown release)
    (is (= [:tp/race 10] [@a (retained-cap :tp/race)]) "the owner's retention policy is final")))

(deftest reregister-owner-rejects-newer-policy-contender
  ;; A pauses after staging, before policy publication; B cannot become a newer winner
  (rf.frame/upsert-frame! :tp/successful
                       {:tags #{:initial}
                        :rf.trace/frame-no-emit? true
                        :rf.trace/events-retained 5})
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a       (binding [rf.frame/*upsert-policy-probe*
                          (window-probe :tp/successful reached release)]
                  (future
                    (rf.frame/upsert-frame! :tp/successful
                                         {:tags #{:a}
                                          :rf.trace/frame-no-emit? false
                                          :rf.trace/events-retained 10})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A reached the pre-policy barrier")
    (is (= :rf.error/frame-construction-in-progress
           (err-id #(rf.frame/upsert-frame! :tp/successful
                                         {:tags #{:b}
                                          :rf.trace/frame-no-emit? true
                                          :rf.trace/events-retained 99}))))
    (.countDown release)
    ;; the record, the no-emit store and the retention store are all A's
    (is (= [:tp/successful #{:a} false 10]
           [@a
            (get-in (rf.frame/frame :tp/successful) [:config :tags])
            (rf.trace/frame-trace-disabled? :tp/successful)
            (retained-cap :tp/successful)]))))

(deftest failed-reregistration-rollback-preserves-prestage-generation
  ;; A reads the final frame and pauses just before its staging swap-vals!; a
  ;; reprojection updates the generation in that window. The hook failure must
  ;; roll back to the value the swap actually replaced, not the record A read.
  (let [id                  :tp/prestage-rollback-merge
        hook-key            :routing/on-frame-registered!
        original-hook       (rf.late-bind/get-fn hook-key)
        original-swap-vals! clojure.core/swap-vals!
        reached             (CountDownLatch. 1)
        release             (CountDownLatch. 1)]
    (rf.frame/upsert-frame! id
                         {:tags #{:prior}
                          :rf.frame/generation :prior-gen
                          :rf.trace/frame-no-emit? true
                          :rf.trace/events-retained 5})
    (let [prior-config       (:config (rf.frame/frame id))
          prior-policy-token (:trace-policy-token (rf.frame/frame id))
          prior-revision     (get-in (rf.frame/frame id)
                                     [:construction :revision])]
      (try
        (rf.late-bind/set-fn!
          hook-key
          (fn [candidate-id]
            (when (= id candidate-id)
              (throw (ex-info "registration hook failed"
                              {:test/outcome :hook-failed})))))
        (with-redefs [clojure.core/swap-vals!
                      (fn [reference f & args]
                        (when (identical? reference rf.frame/frames)
                          (.countDown reached)
                          (.await release 10 TimeUnit/SECONDS))
                        (apply original-swap-vals! reference f args))]
          (let [owner
                (future
                  (try
                    (rf.frame/upsert-frame!
                      id {:tags #{:failed}
                          :rf.frame/generation :failed-gen
                          :rf.trace/frame-no-emit? false
                          :rf.trace/events-retained 99})
                    :unexpected-success
                    (catch clojure.lang.ExceptionInfo e
                      (:test/outcome (ex-data e)))))]
            (try
              (is (.await reached 10 TimeUnit/SECONDS)
                  "A read the prior record and reached the pre-stage swap")
              (rf.frame/set-generation! id :foreign-gen)
              (finally
                (.countDown release)))
            ;; the swap's actual prior generation, and the pre-attempt config,
            ;; policies, policy authority and revision
            (is (= [:hook-failed :foreign-gen prior-config true 5 true true]
                   [@owner
                    (rf.frame/frame-generation id)
                    (:config (rf.frame/frame id))
                    (rf.trace/frame-trace-disabled? id)
                    (retained-cap id)
                    (identical? prior-policy-token (:trace-policy-token (rf.frame/frame id)))
                    (identical? prior-revision
                                (get-in (rf.frame/frame id) [:construction :revision]))]))))
        (finally
          (.countDown release)
          (rf.late-bind/set-fn! hook-key original-hook))))))

;; A re-registration stages its config onto the live record (the same app-db,
;; router, drain lock and sub-cache), so every actor keeps seeing the frame. The
;; owner pauses at *upsert-policy-probe*, after staging; the test thread and the
;; next-tick executor are the foreign actors.

(defn- flush-executor!
  "Block until every task already on the FIFO `next-tick` executor has run."
  []
  (let [p (promise)]
    (rf.interop/next-tick #(deliver p true))
    (deref p 10000 :timeout)))

(defn- router-summary
  "The raw router flags for `id`, read off the registry row."
  [id]
  (let [r @(:router (get @rf.frame/frames id))]
    {:scheduled? (boolean (:scheduled? r))
     :queue-count (count (:queue r))}))

(defn- reg-inc! []
  (rf/reg-event :rereg/inc
    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})))

(deftest reregistration-window-foreign-lookup-and-dispatch-see-the-live-frame
  (let [id      :rereg/visible
        reached (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (reg-inc!)
    (rf.frame/upsert-frame! id {:tags #{:prior}})
    (let [owner (binding [rf.frame/*upsert-policy-probe*
                          (window-probe id reached release)]
                  (future (rf.frame/upsert-frame! id {:tags #{:staged}})))]
      (try
        (is (.await reached 10 TimeUnit/SECONDS) "the owner staged its revision")
        ;; while the revision is provisional, a foreign thread sees the live
        ;; frame with its staged config, enumerates it, and dispatches into it
        (is (= [:provisional #{:staged} true]
               [(get-in @rf.frame/frames [id :construction :state])
                (get-in (rf.frame/frame id) [:config :tags])
                (contains? (rf.frame/frame-ids) id)]))
        (rf/dispatch [:rereg/inc] {:frame id})
        (flush-executor!)
        (is (= {:n 1} (rf.frame/frame-app-db-value id)))
        (finally
          (.countDown release)))
      (is (= [id #{:staged} {:scheduled? false :queue-count 0}]
             [@owner (get-in (rf.frame/frame id) [:config :tags]) (router-summary id)])))))

(deftest reregistration-window-a-drain-starting-inside-it-drains
  (let [id      :rereg/scheduled
        park    (CountDownLatch. 1)
        reached (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (reg-inc!)
    (rf.frame/upsert-frame! id {:tags #{:prior}})
    ;; park the executor so the scheduled drain starts only inside the window
    (rf.interop/next-tick #(.await park 10 TimeUnit/SECONDS))
    (rf/dispatch [:rereg/inc] {:frame id})
    (is (= {:scheduled? true :queue-count 1} (router-summary id))
        "the drain is armed behind the parked executor")
    (let [owner (binding [rf.frame/*upsert-policy-probe*
                          (window-probe id reached release)]
                  (future (rf.frame/upsert-frame! id {:tags #{:staged}})))]
      (try
        (is (.await reached 10 TimeUnit/SECONDS) "the owner staged its revision")
        (.countDown park)
        (flush-executor!)
        (is (= {:n 1} (rf.frame/frame-app-db-value id))
            "the drain processed the event rather than reading the frame as dead")
        (finally
          (.countDown park)
          (.countDown release)))
      (is (= [id {:scheduled? false :queue-count 0}] [@owner (router-summary id)])
          "the router settled, :scheduled? not stuck true")
      (rf/dispatch [:rereg/inc] {:frame id})
      (flush-executor!)
      (is (= {:n 2} (rf.frame/frame-app-db-value id)) "later async dispatches still drain"))))

(deftest reregistration-window-an-in-flight-drain-keeps-its-commit
  (let [id          :rereg/in-flight
        in-handler  (CountDownLatch. 1)
        window-open (CountDownLatch. 1)
        reached     (CountDownLatch. 1)
        release     (CountDownLatch. 1)
        lifecycle   (atom [])]
    (rf/reg-event :rereg/slow
      (fn [{:keys [db]} _]
        (.countDown in-handler)
        (.await window-open 10 TimeUnit/SECONDS)
        {:db (assoc db :slow true)}))
    (rf/reg-event :rereg/after
      (fn [{:keys [db]} _] {:db (assoc db :after true)}))
    (rf.frame/upsert-frame! id {:tags #{:prior}})
    (rf/register-listener! :trace ::in-flight
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (= :rf.frame/drain-interrupted (:operation ev)))
          (swap! lifecycle conj ev))))
    (rf/dispatch [:rereg/slow] {:frame id})
    (rf/dispatch [:rereg/after] {:frame id})
    (is (.await in-handler 10 TimeUnit/SECONDS) "the drain is inside :rereg/slow")
    (let [owner (binding [rf.frame/*upsert-policy-probe*
                          (window-probe id reached release)]
                  (future (rf.frame/upsert-frame! id {:tags #{:staged}})))]
      (try
        (is (.await reached 10 TimeUnit/SECONDS) "the owner staged its revision")
        ;; :rereg/slow returns while the revision is staged
        (.countDown window-open)
        (flush-executor!)
        (is (= {:slow true :after true} (rf.frame/frame-app-db-value id))
            "the in-flight commit stands and the queued event behind it runs")
        (finally
          (.countDown window-open)
          (.countDown release)))
      ;; a hot re-registration is not reported as a destroy
      (is (= [id [] {:scheduled? false :queue-count 0}]
             [@owner @lifecycle (router-summary id)])))))

(deftest reregistration-window-a-foreign-cold-op-is-drain-serialized
  ;; call-serialized-with-drain! resolves the frame first; had the window hidden
  ;; it, the op would run without the drain lock
  (let [id      :rereg/cold
        reached (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (rf.frame/upsert-frame! id {:tags #{:prior}})
    (let [drain-lock (:drain-lock (rf.frame/frame id))
          owner      (binding [rf.frame/*upsert-policy-probe*
                               (window-probe id reached release)]
                       (future (rf.frame/upsert-frame! id {:tags #{:staged}})))]
      (try
        (is (.await reached 10 TimeUnit/SECONDS) "the owner staged its revision")
        (is (true? (rf.frame/call-serialized-with-drain! id (fn [] @drain-lock)))
            "the foreign cold op ran holding the frame's drain lock")
        (finally
          (.countDown release)))
      (is (= [id false] [@owner @drain-lock]) "the cold section released the lock"))))

(deftest failed-reregistration-window-keeps-foreign-work-and-restores-config
  (let [id            :rereg/failed
        hook-key      :routing/on-frame-registered!
        original-hook (rf.late-bind/get-fn hook-key)
        reached       (CountDownLatch. 1)
        release       (CountDownLatch. 1)]
    (reg-inc!)
    (rf.frame/upsert-frame! id {:tags #{:prior}
                                :rf.trace/frame-no-emit? true
                                :rf.trace/events-retained 5})
    (let [prior-config (:config (rf.frame/frame id))]
      (try
        (rf.late-bind/set-fn!
          hook-key
          (fn [candidate-id]
            (when (= id candidate-id)
              (throw (ex-info "registration hook failed"
                              {:test/outcome :hook-failed})))))
        (let [owner (binding [rf.frame/*upsert-policy-probe*
                              (window-probe id reached release)]
                      (future
                        (try
                          (rf.frame/upsert-frame! id {:tags #{:failed}
                                                      :rf.trace/frame-no-emit? false
                                                      :rf.trace/events-retained 99})
                          :unexpected-success
                          (catch clojure.lang.ExceptionInfo e
                            (:test/outcome (ex-data e))))))]
          (try
            (is (.await reached 10 TimeUnit/SECONDS) "the owner staged its revision")
            (is (= #{:failed} (get-in (rf.frame/frame id) [:config :tags]))
                "a foreign thread sees the staged config")
            (rf/dispatch [:rereg/inc] {:frame id})
            (flush-executor!)
            (finally
              (.countDown release)))
          ;; the prior config and policies are restored, while the event that
          ;; ran under the staged config stands and the queue drained
          (is (= [:hook-failed prior-config true 5 {:n 1} {:scheduled? false :queue-count 0}]
                 [@owner
                  (:config (rf.frame/frame id))
                  (rf.trace/frame-trace-disabled? id)
                  (retained-cap id)
                  (rf.frame/frame-app-db-value id)
                  (router-summary id)])))
        (finally
          (.countDown release)
          (rf.late-bind/set-fn! hook-key original-hook))))))

(deftest omitting-retention-on-reregistration-clears-frame-override
  (rf/configure! {:trace-buffer {:events-retained 7}})
  (rf.frame/upsert-frame! :tp/inherit {:rf.trace/events-retained 99})
  (let [installed (retained-cap :tp/inherit)]
    (rf.frame/upsert-frame! :tp/inherit {:tags #{:override-removed}})
    (let [restored  (retained-cap :tp/inherit)
          override? (get-in @@#'re-frame.trace.tooling/trace-rings [:tp/inherit :override?])]
      (rf/configure! {:trace-buffer {:events-retained 3}})
      ;; omission restores the process default and marks the ring inherited, so
      ;; a later default change reaches it
      (is (= [99 7 false 3] [installed restored override? (retained-cap :tp/inherit)])))))
