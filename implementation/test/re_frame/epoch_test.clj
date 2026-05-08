(ns re-frame.epoch-test
  "Tool-Pair §Time-travel — epoch recording, query, listener, restore.

  Bead rf2-shjf coverage:

    1. Recording — drain-settle commits a record; multi-event cascades
       commit one record (not one per event).
    2. Per-frame isolation — two frames, each gets its own ring.
    3. Ring depth cap — dispatch >depth events; oldest get evicted.
    4. Configurable depth — `(rf/configure :epoch-history {:depth N})`.
    5. Listener — `register-epoch-cb` fires per drain-settle with the
       assembled record; same-key replaces; remove unhooks; exception
       isolation.
    6. Restore happy path — `restore-epoch` rewinds app-db.
    7. The six documented failure modes — each fires the documented
       trace under `:rf.epoch/*` and leaves app-db unchanged.
    8. Sub-runs / renders / effects projection from the trace stream."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.trace :as trace]
            [re-frame.epoch :as epoch]))

;; ---- fixtures --------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (epoch/clear-history!)
  (epoch/clear-epoch-cbs!)
  (epoch/configure! {:depth 50})
  (rf/init!)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- record-trace! []
  (let [recorded (atom [])]
    (rf/register-trace-cb! ::recorder (fn [ev] (swap! recorded conj ev)))
    recorded))

(defn- has-error-op? [events op]
  (some (fn [ev] (and (= :error (:op-type ev))
                      (= op     (:operation ev))))
        events))

;; ---- recording -------------------------------------------------------------

(deftest record-on-drain-settle
  (testing "every drain-settle commits exactly one :rf/epoch-record"
    (rf/reg-frame :test/main {:doc "epoch test frame"})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 3 (count history)) "one record per drain-settle")
      (is (every? :epoch-id history))
      (is (every? :committed-at history))
      (is (= [[:seed] [:inc] [:inc]]
             (mapv :trigger-event history))
          "trigger-event preserved per record"))))

(deftest record-shape-canonical
  (testing "an :rf/epoch-record carries the canonical shape"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [r (last (rf/epoch-history :test/main))]
      (is (= :test/main (:frame r)))
      (is (= :inc       (:event-id r)))
      (is (= [:inc]     (:trigger-event r)))
      (is (= {:n 0}     (:db-before r))
          "db-before is the pre-cascade snapshot")
      (is (= {:n 1}     (:db-after r))
          "db-after is the post-settle snapshot")
      (is (vector? (:trace-events r)))
      (is (vector? (:sub-runs r)))
      (is (vector? (:renders r)))
      (is (vector? (:effects r))))))

(deftest record-multi-event-cascade
  (testing "a multi-event cascade (run-to-completion) commits ONE record"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:order []}))
    (rf/reg-event-fx :outer
      (fn [{:keys [db]} _]
        {:db (update db :order conj :outer)
         :fx [[:dispatch [:inner-1]]
              [:dispatch [:inner-2]]]}))
    (rf/reg-event-db :inner-1 (fn [db _] (update db :order conj :inner-1)))
    (rf/reg-event-db :inner-2 (fn [db _] (update db :order conj :inner-2)))

    (rf/dispatch-sync [:seed]  {:frame :test/main})
    (rf/dispatch-sync [:outer] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)
          last-r  (last history)]
      ;; Two cascades: :seed and :outer (which itself cascades inner-1 / inner-2).
      (is (= 2 (count history))
          "the entire :outer cascade is one epoch, not three")
      (is (= :outer (:event-id last-r))
          "the outer event is the trigger of the cascade")
      (is (= {:order []}
             (:db-before last-r)))
      (is (= {:order [:outer :inner-1 :inner-2]}
             (:db-after last-r))))))

;; ---- per-frame isolation ---------------------------------------------------

(deftest per-frame-isolation
  (testing "each frame has its own epoch ring; cascades don't co-mingle"
    (rf/reg-frame :frame/a {})
    (rf/reg-frame :frame/b {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:seed] {:frame :frame/a})
    (rf/dispatch-sync [:inc]  {:frame :frame/a})

    (rf/dispatch-sync [:seed] {:frame :frame/b})
    (rf/dispatch-sync [:inc]  {:frame :frame/b})
    (rf/dispatch-sync [:inc]  {:frame :frame/b})

    (is (= 2 (count (rf/epoch-history :frame/a))))
    (is (= 3 (count (rf/epoch-history :frame/b))))

    (is (every? #(= :frame/a (:frame %)) (rf/epoch-history :frame/a)))
    (is (every? #(= :frame/b (:frame %)) (rf/epoch-history :frame/b)))))

;; ---- ring depth ------------------------------------------------------------

(deftest ring-depth-evicts-oldest
  (testing "when the ring fills, oldest records are evicted FIFO"
    (rf/configure :epoch-history {:depth 3})
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (dotimes [_ 5] (rf/dispatch-sync [:inc] {:frame :test/main}))

    (let [history (rf/epoch-history :test/main)
          dbs     (mapv :db-after history)]
      (is (= 3 (count history)) "ring depth caps history at 3")
      (is (= [{:n 3} {:n 4} {:n 5}] dbs)
          "the three most-recent are kept; oldest evicted"))))

(deftest depth-zero-disables-recording
  (testing "depth 0 disables ring recording"
    (rf/configure :epoch-history {:depth 0})
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (rf/dispatch-sync [:seed] {:frame :test/main})

    (is (= [] (rf/epoch-history :test/main)))))

;; ---- listener --------------------------------------------------------------

(deftest listener-fires-per-drain-settle
  (testing "register-epoch-cb fires once per drain-settle with the assembled record"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (let [seen (atom [])]
      (rf/register-epoch-cb ::watcher (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})

      (is (= 3 (count @seen)))
      (is (= [:seed :inc :inc]
             (mapv :event-id @seen)))
      (is (every? #(contains? % :db-after) @seen))
      (is (every? #(contains? % :sub-runs) @seen))
      (is (every? #(contains? % :renders) @seen))
      (is (every? #(contains? % :effects) @seen))

      (rf/remove-epoch-cb ::watcher))))

(deftest listener-same-key-replaces
  (testing "register-epoch-cb under the same key replaces the prior listener"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [a (atom 0)
          b (atom 0)]
      (rf/register-epoch-cb ::w (fn [_] (swap! a inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (is (= 1 @a))

      (rf/register-epoch-cb ::w (fn [_] (swap! b inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @a) "the original listener no longer fires after re-register under the same key")
      (is (= 1 @b) "the replacement listener fires"))))

(deftest listener-remove
  (testing "remove-epoch-cb stops the listener"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (let [count-a (atom 0)]
      (rf/register-epoch-cb ::w (fn [_] (swap! count-a inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/remove-epoch-cb ::w)
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @count-a) "after removal, the listener does not accumulate"))))

(deftest listener-exception-isolation
  (testing "a throwing epoch listener does not crash other listeners or the runtime"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [survivor (atom 0)
          throws   (atom 0)]
      (rf/register-epoch-cb ::throwing
        (fn [_] (swap! throws inc) (throw (ex-info "tool blew" {}))))
      (rf/register-epoch-cb ::survivor
        (fn [_] (swap! survivor inc)))

      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 2 @throws)   "throwing listener is invoked")
      (is (= 2 @survivor) "survivor listener accumulates")
      (is (= 2 (count (rf/epoch-history :test/main)))
          "epoch history records both cascades despite the throwing listener"))))

;; ---- restore happy path ----------------------------------------------------

(deftest restore-rewinds-app-db
  (testing "restore-epoch sets app-db to the named epoch's :db-after"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})       ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})       ;; n=2
    (rf/dispatch-sync [:inc]  {:frame :test/main})       ;; n=3

    (let [history     (rf/epoch-history :test/main)
          target      (nth history 1)        ;; the :inc that landed n=1
          target-eid  (:epoch-id target)
          recorded    (record-trace!)
          ok?         (rf/restore-epoch :test/main target-eid)]
      (is (true? ok?) "restore returned true")
      (is (= {:n 1} (rf/get-frame-db :test/main))
          "app-db rewound to the named epoch's :db-after")

      (let [events @recorded]
        (is (some (fn [ev]
                    (and (= :rf.epoch/restored (:operation ev))
                         (= target-eid (:epoch-id (:tags ev)))))
                  events)
            ":rf.epoch/restored fired with the matching epoch-id")))))

;; ---- restore failure modes -------------------------------------------------

(deftest restore-failure-unknown-frame
  (testing "restore-epoch on an unknown frame fires :rf.error/no-such-handler (kind :frame)"
    (let [recorded (record-trace!)
          ok?      (rf/restore-epoch :no/such/frame :ignored)]
      (is (false? ok?))
      (let [events @recorded]
        (is (has-error-op? events :rf.error/no-such-handler))
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %) events)]
          (is (= :frame (:kind (:tags ev))))
          (is (= :no/such/frame (:frame-id (:tags ev)))))))))

(deftest restore-failure-unknown-epoch
  (testing "restore-epoch with an epoch-id not in history fires :rf.epoch/restore-unknown-epoch"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [pre        (rf/get-frame-db :test/main)
          recorded   (record-trace!)
          ok?        (rf/restore-epoch :test/main :no-such-epoch)]
      (is (false? ok?))
      (is (= pre (rf/get-frame-db :test/main)) "app-db unchanged")
      (let [events @recorded
            ev     (some #(when (= :rf.epoch/restore-unknown-epoch (:operation %)) %) events)]
        (is (some? ev) ":rf.epoch/restore-unknown-epoch fired")
        (is (= :no-such-epoch (:epoch-id (:tags ev))))
        (is (number? (:history-size (:tags ev))))))))

(deftest restore-failure-schema-mismatch
  (testing "restore-epoch on a db that no longer validates fires :rf.epoch/restore-schema-mismatch"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :set-bad
      ;; Commit a value that LATER fails a tightened schema. We dispatch
      ;; this BEFORE the schema is registered so the pre-restore commit
      ;; succeeds; tightening the schema happens between dispatch and
      ;; restore.
      (fn [db _] (assoc db :n "not-an-int")))

    (rf/dispatch-sync [:seed]    {:frame :test/main})
    (rf/dispatch-sync [:set-bad] {:frame :test/main})
    ;; Reset the db to something valid so we can verify the restore
    ;; doesn't run when schema mismatch is detected.
    (rf/dispatch-sync [:seed]    {:frame :test/main})

    ;; Now register a schema that the bad-record's :db-after fails.
    ;; Per Spec 010 §Per-frame schemas reg-app-schema is frame-scoped;
    ;; restore-epoch runs on :test/main so the schema must register
    ;; against that frame, not the (current-frame)-default :rf/default.
    (rf/reg-app-schema [:n] [:int] {:frame :test/main})

    (let [pre      (rf/get-frame-db :test/main)
          history  (rf/epoch-history :test/main)
          target   (some (fn [r]
                           (when (= "not-an-int" (:n (:db-after r)))
                             r))
                         history)
          recorded (record-trace!)
          ok?      (rf/restore-epoch :test/main (:epoch-id target))]
      (is (some? target) "we recorded the bad-db cascade")
      (is (false? ok?)   "restore rejected")
      (is (= pre (rf/get-frame-db :test/main)) "app-db unchanged")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/restore-schema-mismatch (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/restore-schema-mismatch fired")
        (is (vector? (:failing-paths (:tags ev))))))))

(deftest restore-failure-missing-handler-route
  (testing "restore-epoch on a db referencing a now-unregistered route fires :rf.epoch/restore-missing-handler"
    (rf/reg-frame :test/main {})
    ;; Register a route so the recorded :route reference resolves; we'll
    ;; later unregister it to trigger the missing-handler failure.
    (rf/reg-route :route/users {:path "/users"})
    (rf/reg-event-db :route-to (fn [db _] (assoc db :route {:id :route/users})))
    (rf/dispatch-sync [:route-to] {:frame :test/main})
    ;; A subsequent dispatch so the history holds at least one record
    ;; whose :db-after references :route/users.
    (let [target (last (rf/epoch-history :test/main))]
      ;; Now blow away the route registration.
      (registrar/unregister! :route :route/users)

      (let [recorded (record-trace!)
            pre      (rf/get-frame-db :test/main)
            ok?      (rf/restore-epoch :test/main (:epoch-id target))]
        (is (false? ok?))
        (is (= pre (rf/get-frame-db :test/main)) "app-db unchanged")
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-missing-handler (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-missing-handler fired")
          (let [missing (:missing (:tags ev))]
            (is (vector? missing))
            (is (some #(and (= :route (:kind %))
                            (= :route/users (:id %)))
                      missing))))))))

(deftest restore-failure-missing-handler-machine
  (testing "restore-epoch on a db referencing a machine snapshot whose machine
  is no longer registered fires :rf.epoch/restore-missing-handler. Per
  rf2-ocg1: machine resolution goes through the public event registry
  (:rf/machine? metadata), NOT the internal :head registrar kind."
    (rf/reg-frame :test/main {})
    ;; Register a machine via the public reg-machine path. This installs an
    ;; :event handler with :rf/machine? metadata.
    (rf/reg-machine :machine/tl
      {:initial :red
       :states  {:red    {:on {:tick :green}}
                 :green  {:on {:tick :red}}}})
    ;; Drive the machine so :rf/machines :machine/tl gets a snapshot.
    (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})

    (let [target (last (rf/epoch-history :test/main))]
      (is (some? (get-in (:db-after target) [:rf/machines :machine/tl]))
          "snapshot recorded under :rf/machines")

      ;; Unregister the machine so the recorded snapshot's id no longer resolves.
      (registrar/unregister! :event :machine/tl)

      (let [recorded (record-trace!)
            pre      (rf/get-frame-db :test/main)
            ok?      (rf/restore-epoch :test/main (:epoch-id target))]
        (is (false? ok?))
        (is (= pre (rf/get-frame-db :test/main)) "app-db unchanged")
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-missing-handler (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-missing-handler fired")
          (let [missing (:missing (:tags ev))]
            (is (vector? missing))
            (is (some #(and (= :machine (:kind %))
                            (= :machine/tl (:id %)))
                      missing)
                "missing entry surfaces the machine id under :machine kind")))))))

(deftest restore-failure-missing-handler-non-machine-event-not-confused
  (testing "an event handler under the same id as a recorded machine snapshot —
  but NOT marked :rf/machine? — does not satisfy the machine reference. Per
  rf2-ocg1, the registry probe gates on :rf/machine? metadata."
    (rf/reg-frame :test/main {})
    ;; Register a machine, drive it, then replace its registration with a
    ;; plain event handler (no :rf/machine? metadata). The recorded snapshot
    ;; should still surface as missing, since the public contract says the
    ;; reference must resolve to a registered MACHINE — not a same-id event.
    (rf/reg-machine :machine/tl
      {:initial :red
       :states  {:red {:on {:tick :green}} :green {}}})
    (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})

    (let [target (last (rf/epoch-history :test/main))]
      (rf/reg-event-db :machine/tl (fn [db _] db)) ;; replace with non-machine handler

      (let [recorded (record-trace!)
            ok?      (rf/restore-epoch :test/main (:epoch-id target))]
        (is (false? ok?))
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-missing-handler (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev))
          (is (some #(and (= :machine (:kind %))
                          (= :machine/tl (:id %)))
                    (:missing (:tags ev)))))))))

(deftest restore-failure-version-mismatch
  (testing "restore-epoch on a db whose machine snapshot version drifts fires
  :rf.epoch/restore-version-mismatch. Per rf2-ocg1: the recorded snapshot's
  [:meta :rf/snapshot-version] is compared against the registered machine's
  [:meta :rf/snapshot-version], both via the public Spec 005 surface."
    (rf/reg-frame :test/main {})
    ;; Register a versioned machine via the public path.
    (rf/reg-machine :machine/tl
      {:initial :red
       :meta    {:rf/snapshot-version 1}
       :states  {:red {:on {:tick :green}} :green {}}})
    ;; Commit a snapshot carrying matching :meta :rf/snapshot-version.
    (rf/reg-event-db :put-snap
      (fn [db _]
        (assoc-in db [:rf/machines :machine/tl]
                  {:state :red :data {} :meta {:rf/snapshot-version 1}})))
    (rf/dispatch-sync [:put-snap] {:frame :test/main})

    (let [target (last (rf/epoch-history :test/main))]
      ;; Hot-reload bumps the machine definition's version.
      (rf/reg-machine :machine/tl
        {:initial :red
         :meta    {:rf/snapshot-version 2}
         :states  {:red {:on {:tick :green}} :green {}}})

      (let [recorded (record-trace!)
            pre      (rf/get-frame-db :test/main)
            ok?      (rf/restore-epoch :test/main (:epoch-id target))]
        (is (false? ok?))
        (is (= pre (rf/get-frame-db :test/main)))
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-version-mismatch (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-version-mismatch fired")
          (is (= :machine/tl (:machine-id (:tags ev))))
          (is (= 1 (:version-recorded (:tags ev))))
          (is (= 2 (:version-current  (:tags ev)))))))))

(deftest restore-version-legacy-snapshot-slot-tolerated
  (testing "snapshots written before :rf/snapshot-version moved into :meta
  remain comparable. The legacy top-level slot resolves to the same recorded
  version. Per rf2-ocg1 (back-compat tolerance)."
    (rf/reg-frame :test/main {})
    (rf/reg-machine :machine/tl
      {:initial :red
       :meta    {:rf/snapshot-version 1}
       :states  {:red {} :green {}}})
    ;; Commit a snapshot with the LEGACY top-level slot.
    (rf/reg-event-db :put-snap
      (fn [db _]
        (assoc-in db [:rf/machines :machine/tl]
                  {:state :red :data {} :rf/snapshot-version 1})))
    (rf/dispatch-sync [:put-snap] {:frame :test/main})

    (let [target (last (rf/epoch-history :test/main))]
      ;; Bump definition version.
      (rf/reg-machine :machine/tl
        {:initial :red
         :meta    {:rf/snapshot-version 2}
         :states  {:red {} :green {}}})

      (let [recorded (record-trace!)
            ok?      (rf/restore-epoch :test/main (:epoch-id target))]
        (is (false? ok?))
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-version-mismatch (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev))
          (is (= 1 (:version-recorded (:tags ev))))
          (is (= 2 (:version-current  (:tags ev)))))))))

(deftest restore-failure-during-drain
  (testing "restore-epoch called from inside a drain fires :rf.epoch/restore-during-drain"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [target   (last (rf/epoch-history :test/main))
          recorded (record-trace!)
          attempt  (atom nil)]
      ;; A handler that calls restore-epoch synchronously during a drain.
      (rf/reg-event-db :try-restore
        (fn [db _]
          (reset! attempt (rf/restore-epoch :test/main (:epoch-id target)))
          (assoc db :n 99)))
      (rf/dispatch-sync [:try-restore] {:frame :test/main})

      (is (false? @attempt) "restore returned false from inside the drain")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/restore-during-drain (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/restore-during-drain fired")
        (is (= :test/main (:frame (:tags ev))))))))

;; ---- structured projections ------------------------------------------------

(deftest sub-runs-projection
  (testing ":sub-runs reflects each :sub/run trace under the cascade"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-sub :n     (fn [db _] (:n db)))
    (rf/reg-sub :n*2   :<- [:n] (fn [n _] (* 2 (or n 0))))

    ;; Force a sub-run inside a handler (subscribe-value emits :sub/run
    ;; from compute-sub when no cache exists for the query, AND from the
    ;; reactive path when a fresh subscription materialises). Either
    ;; way, the cascade contains :sub/run traces.
    (rf/reg-event-fx :read-sub
      (fn [_ _]
        ;; Read both subs to exercise layer-1 and layer-2.
        (let [_v (rf/subscribe-value :test/main [:n*2])]
          {})))

    (rf/dispatch-sync [:seed]      {:frame :test/main})
    (rf/dispatch-sync [:read-sub]  {:frame :test/main})

    (let [r        (last (rf/epoch-history :test/main))
          sub-runs (:sub-runs r)]
      (is (vector? sub-runs))
      (is (some #(= :n   (:sub-id %)) sub-runs))
      (is (some #(= :n*2 (:sub-id %)) sub-runs))
      (is (every? :recomputed? sub-runs))
      ;; rf2-7e2y: :result-changed? was structurally always true (only
      ;; recomputed subs emit :sub/run under rf2-719e value-equality
      ;; suppression) and has been dropped — every entry must NOT carry
      ;; the slot.
      (is (every? #(not (contains? % :result-changed?)) sub-runs)))))

(deftest effects-projection-skipped-on-platform
  (testing ":effects captures :skipped-on-platform outcomes"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :client-only-fx {:platforms #{:client}}
               (fn [_ _] :nope))
    (rf/reg-event-fx :run
      (fn [_ _] {:fx [[:client-only-fx :payload]]}))

    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)
          ent     (some #(when (= :client-only-fx (:fx-id %)) %) effects)]
      (is (some? ent) ":client-only-fx surfaces in :effects")
      (is (= :skipped-on-platform (:outcome ent))))))

(deftest effects-projection-no-such-fx
  (testing ":effects captures :error outcomes for unknown fx-ids"
    (rf/reg-frame :test/main {})
    (rf/reg-event-fx :run
      (fn [_ _] {:fx [[:no/such-fx :payload]]}))
    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)
          ent     (some #(when (= :no/such-fx (:fx-id %)) %) effects)]
      (is (some? ent))
      (is (= :error (:outcome ent)))
      (is (some? (:error-trace ent))))))

(deftest effects-projection-fx-handler-exception
  (testing ":effects captures :error outcomes for fx that throw"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "boom" {}))))
    (rf/reg-event-fx :run
      (fn [_ _] {:fx [[:throwing-fx :payload]]}))

    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)
          ent     (some #(when (= :throwing-fx (:fx-id %)) %) effects)]
      (is (some? ent))
      (is (= :error (:outcome ent)))
      (is (some? (:error-trace ent))))))

(deftest effects-projection-records-success
  (testing ":effects captures :ok outcomes for successful user fx (rf2-rrgq)"
    (rf/reg-frame :test/main {})
    (let [calls (atom 0)]
      (rf/reg-fx :tally-fx (fn [_ args] (swap! calls + args)))
      (rf/reg-event-fx :run
        (fn [_ _] {:fx [[:tally-fx 5]]}))

      (rf/dispatch-sync [:run] {:frame :test/main})

      (is (= 5 @calls) "the fx ran")
      (let [r       (last (rf/epoch-history :test/main))
            effects (:effects r)
            ent     (some #(when (= :tally-fx (:fx-id %)) %) effects)]
        (is (some? ent) ":tally-fx surfaces in :effects on success")
        (is (= :ok (:outcome ent)))
        (is (= 5   (:args ent)))
        (is (not (contains? ent :error-trace))
            ":ok entries don't carry :error-trace")))))

(deftest effects-projection-records-reserved-fx-success
  (testing ":effects captures :ok outcomes for reserved fx-ids (:dispatch)"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed   (fn [_ _]   {:n 0}))
    (rf/reg-event-db :inc    (fn [db _]  (update db :n inc)))
    (rf/reg-event-fx :outer
      (fn [_ _] {:fx [[:dispatch [:inc]]
                      [:dispatch [:inc]]]}))

    (rf/dispatch-sync [:seed]  {:frame :test/main})
    (rf/dispatch-sync [:outer] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)
          dispatches (filterv #(= :dispatch (:fx-id %)) effects)]
      (is (= 2 (count dispatches))
          "two :dispatch fx → two :effects entries")
      (is (every? #(= :ok (:outcome %)) dispatches)))))

(deftest effects-projection-one-entry-per-fx
  (testing "an epoch with N dispatched fx produces N :effects entries (rf2-rrgq)"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :ok-fx       (fn [_ _] :ok))
    (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "boom" {}))))
    (rf/reg-fx :client-only {:platforms #{:client}}
               (fn [_ _] :nope))
    (rf/reg-event-fx :run
      (fn [_ _] {:fx [[:ok-fx       :a]
                      [:throwing-fx :b]
                      [:no/such-fx  :c]
                      [:client-only :d]
                      [:ok-fx       :e]]}))

    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)]
      (is (= 5 (count effects))
          "five dispatched fx → five projection entries, no double-count")
      (is (= [:ok :error :error :skipped-on-platform :ok]
             (mapv :outcome effects))
          "outcomes preserved in dispatch order")
      (is (= [:ok-fx :throwing-fx :no/such-fx :client-only :ok-fx]
             (mapv :fx-id effects))
          "fx-ids preserved in dispatch order"))))

;; ---- partial-drain semantics -----------------------------------------------

(deftest depth-exceeded-discards-buffer
  (testing "a depth-exceeded drain does NOT commit a partial epoch record"
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event-fx :loop
      (fn [_ _] {:fx [[:dispatch [:loop]]]}))

    (rf/dispatch-sync [:loop] {:frame :test/main})

    ;; The drain hit depth 5 and discarded; no epoch record was committed
    ;; (the cascade never settled cleanly).
    (is (= [] (rf/epoch-history :test/main))
        "no record committed for partial drains")))

;; ---- recording is gated on debug-enabled? ---------------------------------

(deftest configure-roundtrip
  (testing "(rf/configure :epoch-history {:depth N}) updates the depth"
    (rf/configure :epoch-history {:depth 7})
    (is (= 7 (:depth (epoch/current-config))))
    (rf/configure :epoch-history {:depth 12})
    (is (= 12 (:depth (epoch/current-config))))))
