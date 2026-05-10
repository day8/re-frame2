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
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.epoch :as epoch]
            ;; rf2-v6z0: machines is a separate artefact whose late-bind
            ;; hook publishes `rf/reg-machine` only when the namespace is
            ;; loaded. Several restore-* tests register machines via
            ;; `rf/reg-machine` in their bodies; without this require they
            ;; throw `:rf.error/machines-artefact-missing`. Side-effect
            ;; require — the namespace alias is unused.
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  ;; Per smoke_test.clj fixture (rf2-xsfj): flows.cljc keeps a private
  ;; last-inputs atom for dirty-checking. Clear it so a prior test's
  ;; flow registration can't leak its last-inputs into a same-keyed
  ;; flow in a subsequent test.
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-trace-cbs!)
  (epoch/clear-history!)
  (epoch/clear-epoch-cbs!)
  (epoch/configure! {:depth 50})
  (rf/init! plain-atom/adapter)
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

(deftest restore-schema-mismatch-trace-carries-digests
  (testing "Per Spec 010 §Schema digest + Tool-Pair §Time-travel (rf2-0z1z):
            the :rf.epoch/restore-schema-mismatch trace carries non-nil
            :schema-digest-recorded and :schema-digest-current tags."
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed    (fn [_ _]  {:n 0}))
    (rf/reg-event-db :set-bad (fn [db _] (assoc db :n "not-an-int")))

    ;; Record an epoch with NO schemas registered yet — its
    ;; :schema-digest is the empty-set digest (still non-nil — Spec 010
    ;; defines the empty set's digest).
    (rf/dispatch-sync [:seed]    {:frame :test/main})
    (rf/dispatch-sync [:set-bad] {:frame :test/main})
    (rf/dispatch-sync [:seed]    {:frame :test/main})

    ;; Tighten the schema set — the recorded epoch's digest now
    ;; differs from the live (current) digest.
    (rf/reg-app-schema [:n] [:int] {:frame :test/main})

    (let [history  (rf/epoch-history :test/main)
          target   (some (fn [r]
                           (when (= "not-an-int" (:n (:db-after r)))
                             r))
                         history)
          recorded (record-trace!)
          _        (rf/restore-epoch :test/main (:epoch-id target))
          ev       (some (fn [ev]
                           (when (= :rf.epoch/restore-schema-mismatch (:operation ev))
                             ev))
                         @recorded)
          tags     (:tags ev)]
      (is (some? ev) ":rf.epoch/restore-schema-mismatch fired")
      (is (string? (:schema-digest-recorded tags))
          ":schema-digest-recorded is a digest string, not nil")
      (is (string? (:schema-digest-current tags))
          ":schema-digest-current is a digest string, not nil")
      (is (re-matches #"sha256:[0-9a-f]{16}" (:schema-digest-recorded tags))
          ":schema-digest-recorded matches the canonical wire form")
      (is (re-matches #"sha256:[0-9a-f]{16}" (:schema-digest-current tags))
          ":schema-digest-current matches the canonical wire form")
      (is (not= (:schema-digest-recorded tags)
                (:schema-digest-current tags))
          "recorded ≠ current — that's *why* the restore was rejected"))))

(deftest epoch-record-stamps-schema-digest
  (testing "Per Spec-Schemas §:rf/epoch-record (rf2-0z1z): every epoch
            record carries a :schema-digest pinned at record time."
    (rf/reg-frame :test/digest {})
    (rf/reg-app-schema [:n] [:int] {:frame :test/digest})
    (rf/reg-event-db :init (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:init] {:frame :test/digest})
    (let [r (last (rf/epoch-history :test/digest))]
      (is (some? r) "an epoch record was committed")
      (is (string? (:schema-digest r))
          "the record carries a :schema-digest string")
      (is (= (:schema-digest r)
             (rf/app-schemas-digest :test/digest))
          "record's stamp matches the live digest at record time"))))

(deftest restore-failure-missing-handler-route
  (testing "restore-epoch on a db referencing a now-unregistered route fires :rf.epoch/restore-missing-handler"
    (rf/reg-frame :test/main {})
    ;; Register a route so the recorded :rf/route reference resolves; we'll
    ;; later unregister it to trigger the missing-handler failure.
    (rf/reg-route :route/users {:path "/users"})
    (rf/reg-event-db :route-to (fn [db _] (assoc db :rf/route {:id :route/users})))
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

;; ---- restore-epoch reactive surfaces (rf2-2fat) ---------------------------
;;
;; Bead rf2-2fat coverage. Tool-Pair §Time-travel says restore "rewinds the
;; frame's app-db to the named epoch's :db-after value." Spec 006 §Subscription
;; cache pins invalidation to :replace-container! — and restore-epoch goes
;; through the same adapter/replace-container! choke point used by the drain
;; loop's :db commit. The two together imply: every reactive surface that
;; observes app-db (subscriptions, flows materialised at :path, route slice
;; reads) must reflect the rewound value after restore-epoch returns true,
;; without a separate cache-invalidation call.
;;
;; These tests pin that downstream contract on the JVM with the plain-atom
;; adapter. The plain-atom adapter recomputes derived values on every deref
;; (no cache), so subscribe-value before/after restore is a clean read of
;; the post-restore container. The CLJS Reagent counterpart in
;; runtime_cljs_test.cljs covers the reactive-graph case where a held
;; reaction must observe the rewound value.

(deftest restore-rewinds-subscriptions-via-subscribe-value
  (testing "after restore-epoch, subscribe-value reflects the restored db
  for both layer-1 and layer-2 subs (no manual cache invalidation)."
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n   (fn [db _] (:n db)))
    (rf/reg-sub :n*2 :<- [:n] (fn [n _] (* 2 (or n 0))))

    (rf/dispatch-sync [:seed] {:frame :test/main})  ;; n=0
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=2
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=3

    (is (= 3 (rf/subscribe-value :test/main [:n])))
    (is (= 6 (rf/subscribe-value :test/main [:n*2])))

    (let [history (rf/epoch-history :test/main)
          ;; Pick the epoch where :n landed at 1 (second :inc dispatch).
          target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) history)]
      (is (true? (rf/restore-epoch :test/main (:epoch-id target))))
      (is (= 1 (rf/subscribe-value :test/main [:n]))
          "layer-1 sub now sees the restored value (no manual invalidation)")
      (is (= 2 (rf/subscribe-value :test/main [:n*2]))
          "layer-2 sub recomputes against the restored input"))))

(deftest restore-rewinds-pinned-reaction
  (testing "a subscription held across restore re-derefs to the restored
  value. Pins the contract that restore-epoch goes through the same
  app-db write path as the drain loop, so any consumer holding a
  subscription before the restore observes the rewind on the next deref
  without re-subscribing."
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=2

    (let [pinned  (rf/subscribe :test/main [:n])
          _       (is (= 2 @pinned) "pinned reaction sees current value")
          history (rf/epoch-history :test/main)
          target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) history)]
      (is (true? (rf/restore-epoch :test/main (:epoch-id target))))
      (is (= 1 @pinned)
          "the same reaction handle now derefs to the restored value")
      (rf/unsubscribe :test/main [:n]))))

(deftest restore-frame-isolation
  (testing "restoring frame A leaves frame B's app-db and subscriptions
  untouched. Per Tool-Pair §Time-travel: time-travel is a frame-local
  primitive — there is no global epoch sequence."
    (rf/reg-frame :frame/a {})
    (rf/reg-frame :frame/b {})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))

    ;; Drive A through 0 → 1 → 2; B through 100 → 101.
    (rf/dispatch-sync [:seed 0]   {:frame :frame/a})
    (rf/dispatch-sync [:inc]      {:frame :frame/a})
    (rf/dispatch-sync [:inc]      {:frame :frame/a})
    (rf/dispatch-sync [:seed 100] {:frame :frame/b})
    (rf/dispatch-sync [:inc]      {:frame :frame/b})

    (let [a-history (rf/epoch-history :frame/a)
          ;; The epoch where A's n landed at 1 (first :inc).
          a-target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) a-history)]
      (is (true? (rf/restore-epoch :frame/a (:epoch-id a-target))))
      (is (= 1   (rf/subscribe-value :frame/a [:n]))
          "frame A's sub sees the rewound value")
      (is (= 101 (rf/subscribe-value :frame/b [:n]))
          "frame B's sub is unchanged by the cross-frame restore")
      (is (= 101 (:n (rf/get-frame-db :frame/b)))
          "frame B's app-db is unchanged"))))

(deftest restore-fixed-point-same-epoch-twice
  (testing "restoring twice to the same epoch is a no-op semantically:
  the second call lands on the same db-after, every observable surface
  reads the same value, and the call still returns true."
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=2
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=3

    (let [history (rf/epoch-history :test/main)
          target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) history)
          target-eid (:epoch-id target)]
      (is (true? (rf/restore-epoch :test/main target-eid)) "first restore ok")
      (let [db-after-1 (rf/get-frame-db :test/main)
            sub-1      (rf/subscribe-value :test/main [:n])]
        (is (= {:n 1} db-after-1))
        (is (= 1     sub-1))

        ;; Restore again to the SAME epoch.
        (is (true? (rf/restore-epoch :test/main target-eid)) "second restore ok")
        (is (= db-after-1 (rf/get-frame-db :test/main))
            "app-db unchanged across the second restore")
        (is (= sub-1 (rf/subscribe-value :test/main [:n]))
            "sub value unchanged across the second restore")))))

(deftest restore-rewinds-flow-output-in-app-db
  (testing "Per Spec 013 — a flow's value lives in app-db at :path, where
  it 'survives ... time-travel revert.' After restore-epoch, the flow's
  output reads through the restored db match the recorded epoch's output."
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :init (fn [_ _]      {:w 2 :h 3}))
    (rf/reg-event-db :w!   (fn [db [_ w]] (assoc db :w w)))
    (rf/reg-event-db :h!   (fn [db [_ h]] (assoc db :h h)))
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :test/main})

    (rf/dispatch-sync [:init]    {:frame :test/main})  ;; area=6
    (rf/dispatch-sync [:w! 5]    {:frame :test/main})  ;; area=15
    (rf/dispatch-sync [:h! 10]   {:frame :test/main})  ;; area=50
    (is (= 50 (get-in (rf/get-frame-db :test/main) [:rect :area])))

    (let [history (rf/epoch-history :test/main)
          ;; Find the epoch whose db-after has area=15 (after :w! 5).
          target  (some (fn [r] (when (= 15 (get-in (:db-after r) [:rect :area])) r))
                        history)]
      (is (some? target))
      (is (true? (rf/restore-epoch :test/main (:epoch-id target))))
      (is (= 15 (get-in (rf/get-frame-db :test/main) [:rect :area]))
          "the restored db carries the flow's value at :path"))))

(deftest restore-then-dispatch-recomputes-flow-correctly
  (testing "After restore, the next event drain re-runs flows correctly.
  The dirty-check operates on observed inputs (:w, :h) vs last-inputs;
  even if last-inputs holds a pre-restore tuple, a real input change
  in the next dispatch triggers recomputation. Pins that flow recompute
  state does not silently miss after a restore."
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :init (fn [_ _]      {:w 1 :h 1}))
    (rf/reg-event-db :w!   (fn [db [_ w]] (assoc db :w w)))
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :test/main})

    (rf/dispatch-sync [:init]   {:frame :test/main})  ;; area=1
    (rf/dispatch-sync [:w! 4]   {:frame :test/main})  ;; area=4
    (rf/dispatch-sync [:w! 7]   {:frame :test/main})  ;; area=7
    (rf/dispatch-sync [:w! 9]   {:frame :test/main})  ;; area=9

    (let [history (rf/epoch-history :test/main)
          target  (some (fn [r] (when (= 4 (get-in (:db-after r) [:rect :area])) r))
                        history)]
      (is (true? (rf/restore-epoch :test/main (:epoch-id target))))
      (is (= 4 (get-in (rf/get-frame-db :test/main) [:rect :area]))
          "restored db carries the flow output value at :path")

      ;; Drive a new event that changes :w. The flow must recompute
      ;; against the post-restore inputs, not against any leftover
      ;; last-inputs cache from the pre-restore history.
      (rf/dispatch-sync [:w! 6] {:frame :test/main})
      (is (= 6 (get-in (rf/get-frame-db :test/main) [:w])))
      (is (= 6 (get-in (rf/get-frame-db :test/main) [:rect :area]))
          "flow recomputed correctly post-restore (6 * 1 = 6)"))))

(deftest restore-rewinds-route-slice-and-route-sub
  (testing "Per the bead: when a restored epoch changes :route, the
  observable routing state follows. A sub keyed on the :route slice
  returns the restored route id without manual cache invalidation."
    (rf/reg-frame :test/main {})
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/article {:path "/articles/:id"})
    (rf/reg-event-db :go-home
      (fn [db _] (assoc db :rf/route {:id :route/home :params {}})))
    (rf/reg-event-db :go-article
      (fn [db [_ id]] (assoc db :rf/route {:id :route/article :params {:id id}})))
    (rf/reg-sub :current-route (fn [db _] (get-in db [:rf/route :id])))

    (rf/dispatch-sync [:go-home]               {:frame :test/main})
    (rf/dispatch-sync [:go-article "intro"]    {:frame :test/main})
    (is (= :route/article (rf/subscribe-value :test/main [:current-route])))

    (let [history (rf/epoch-history :test/main)
          ;; The epoch whose db-after carries :route/home in :rf/route :id.
          target  (some (fn [r]
                          (when (= :route/home (get-in (:db-after r) [:rf/route :id]))
                            r))
                        history)]
      (is (some? target))
      (is (true? (rf/restore-epoch :test/main (:epoch-id target))))
      (is (= :route/home (get-in (rf/get-frame-db :test/main) [:rf/route :id]))
          "the :rf/route slice is rewound by restore")
      (is (= :route/home (rf/subscribe-value :test/main [:current-route]))
          "a sub keyed on :rf/route returns the restored value"))))

;; ---- reset-frame-db! (Tool-Pair §Pair-tool writes, rf2-zq55) -------------
;;
;; Per Tool-Pair §Pair-tool writes: reset-frame-db! is the canonical
;; Tool-Pair write surface for state injection. The invariants below
;; cover the contract the spec commits to:
;;
;; 1. Replaces the frame's app-db with new-db.
;; 2. Records a synthetic :rf/epoch-record so restore-epoch can rewind.
;; 3. Drain-check: rejects a call from inside a drain.
;; 4. Schema validation: rejects a new-db that fails the frame's
;;    registered app-schemas.
;; 5. Trace emission: :rf.epoch/db-replaced fires on success with
;;    :frame and :epoch-id.
;; 6. Listeners: register-epoch-cb fires with the assembled record.
;; 7. Unknown frame: :rf.error/no-such-handler (kind :frame).

(deftest reset-frame-db!-replaces-container
  (testing "reset-frame-db! replaces the underlying app-db value"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (= {:n 0} (rf/get-frame-db :test/main)))

    (is (true? (rf/reset-frame-db! :test/main {:n 99 :injected? true})))
    (is (= {:n 99 :injected? true} (rf/get-frame-db :test/main))
        "container holds the injected value")))

(deftest reset-frame-db!-records-undo-epoch
  (testing "reset-frame-db! records a synthetic epoch so restore-epoch
            can rewind to the prior state"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [pre-history-count (count (rf/epoch-history :test/main))
          _                 (rf/reset-frame-db! :test/main {:n 999})
          history           (rf/epoch-history :test/main)
          fresh-record      (last history)]
      (is (= (inc pre-history-count) (count history))
          "a new record was appended")
      (is (= :rf.epoch/db-replaced (:event-id fresh-record))
          "the synthetic record's event-id sentinels the pair-tool injection")
      (is (= [:rf.epoch/db-replaced] (:trigger-event fresh-record))
          "trigger-event mirrors the sentinel")
      (is (= {:n 7}   (:db-before fresh-record)) "db-before captured")
      (is (= {:n 999} (:db-after fresh-record))  "db-after captured")

      ;; restore-epoch on the synthetic record rewinds to db-after of
      ;; the synthetic record (not its db-before). To rewind PAST the
      ;; injection, the caller restores an earlier epoch in the history.
      (let [pre-injection (some (fn [r]
                                  (when (= :seed (:event-id r)) r))
                                history)]
        (is (some? pre-injection))
        (is (true? (rf/restore-epoch :test/main (:epoch-id pre-injection))))
        (is (= {:n 7} (rf/get-frame-db :test/main))
            "restoring the seed epoch rewinds past the pair-tool injection")))))

(deftest reset-frame-db!-emits-trace
  (testing "reset-frame-db! emits :rf.epoch/db-replaced on success with
            :frame and :epoch-id tags"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [recorded (record-trace!)
          _        (rf/reset-frame-db! :test/main {:n 1})
          ev       (some (fn [ev]
                           (when (= :rf.epoch/db-replaced (:operation ev))
                             ev))
                         @recorded)]
      (is (some? ev) ":rf.epoch/db-replaced fired")
      (is (= :rf.epoch (:op-type ev)))
      (is (= :test/main (:frame (:tags ev))))
      (is (number? (:epoch-id (:tags ev)))
          "trace carries the synthetic record's epoch-id"))))

(deftest reset-frame-db!-fires-listeners
  (testing "reset-frame-db! fans out the assembled synthetic record to
            register-epoch-cb listeners"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [received (atom [])]
      (rf/register-epoch-cb ::reset-listener
                            (fn [r] (swap! received conj r)))
      (try
        (rf/reset-frame-db! :test/main {:n 42})
        (let [r (last @received)]
          (is (some? r) "a record was delivered to the listener")
          (is (= :test/main (:frame r)))
          (is (= :rf.epoch/db-replaced (:event-id r)))
          (is (= {:n 42} (:db-after r)))
          (is (= {:n 0}  (:db-before r))))
        (finally
          (rf/remove-epoch-cb ::reset-listener))))))

(deftest reset-frame-db!-failure-unknown-frame
  (testing "reset-frame-db! on an unknown frame returns false and emits
            :rf.error/no-such-handler (kind :frame); no-op on app-db"
    (let [recorded (record-trace!)
          ok?      (rf/reset-frame-db! :no/such/frame {:any 'value})]
      (is (false? ok?))
      (is (has-error-op? @recorded :rf.error/no-such-handler))
      (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                     @recorded)]
        (is (= :frame (:kind (:tags ev))))
        (is (= :no/such/frame (:frame-id (:tags ev))))))))

(deftest reset-frame-db!-failure-during-drain
  (testing "reset-frame-db! called from inside a drain returns false and
            emits :rf.epoch/reset-frame-db-during-drain; app-db unchanged
            by the rejected call"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [recorded (record-trace!)
          attempt  (atom nil)]
      (rf/reg-event-db :try-reset
        (fn [db _]
          (reset! attempt (rf/reset-frame-db! :test/main {:n 999}))
          ;; If reset succeeded we'd see {:n 999} after settle (the
          ;; drain's :db-after returned by this handler is {:n 0}).
          db))
      (rf/dispatch-sync [:try-reset] {:frame :test/main})

      (is (false? @attempt) "reset returned false from inside the drain")
      (is (= {:n 0} (rf/get-frame-db :test/main))
          "app-db unchanged — the in-drain reset was rejected")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/reset-frame-db-during-drain
                                (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/reset-frame-db-during-drain fired")
        (is (= :test/main (:frame (:tags ev))))))))

(deftest reset-frame-db!-failure-schema-mismatch
  (testing "reset-frame-db! with a new-db that fails the frame's
            registered schemas returns false; emits
            :rf.epoch/reset-frame-db-schema-mismatch; app-db unchanged"
    (rf/reg-frame :test/main {})
    ;; Per Spec 010 §Per-frame schemas — schema is frame-scoped.
    (rf/reg-app-schema [:n] [:int] {:frame :test/main})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [pre      (rf/get-frame-db :test/main)
          recorded (record-trace!)
          ok?      (rf/reset-frame-db! :test/main {:n "not-an-int"})]
      (is (false? ok?) "reset rejected on schema mismatch")
      (is (= pre (rf/get-frame-db :test/main))
          "app-db unchanged after a rejected reset")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/reset-frame-db-schema-mismatch
                                (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/reset-frame-db-schema-mismatch fired")
        (is (= :test/main (:frame (:tags ev))))
        (is (vector? (:failing-paths (:tags ev)))
            "trace carries the failing schema paths")
        (is (some #{[:n]} (:failing-paths (:tags ev)))
            "[:n] is the failing path")))))

(deftest reset-frame-db!-no-validation-when-no-schemas
  (testing "When the frame has no registered schemas, reset-frame-db!
            accepts any new-db (the validation step is a no-op)"
    (rf/reg-frame :test/loose {})
    (rf/reg-event-db :seed (fn [_ _] {:anything 'goes}))
    (rf/dispatch-sync [:seed] {:frame :test/loose})

    (is (true? (rf/reset-frame-db! :test/loose {:totally :different :shape true})))
    (is (= {:totally :different :shape true}
           (rf/get-frame-db :test/loose)))))

(deftest reset-frame-db!-subs-re-fire
  (testing "Subscribers route off the post-reset app-db value (the
            substrate's reactive container drives sub re-evaluation,
            same as restore-epoch's happy path)"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :n*2 :<- [:n] (fn [n _] (* 2 (or n 0))))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (= 0 (rf/subscribe-value :test/main [:n])))
    (is (= 0 (rf/subscribe-value :test/main [:n*2])))

    (rf/reset-frame-db! :test/main {:n 21})
    (is (= 21 (rf/subscribe-value :test/main [:n]))
        "layer-1 sub returns the post-reset value")
    (is (= 42 (rf/subscribe-value :test/main [:n*2]))
        "derived sub re-computes against the post-reset value")))

(deftest reset-frame-db!-raises-when-epoch-artefact-missing
  (testing "Per the rf2-5b6x missing-artefact error contract:
            rf/reset-frame-db! raises :rf.error/epoch-artefact-missing
            when the :epoch/reset-frame-db! late-bind hook is nil
            (i.e. the day8/re-frame-2-epoch artefact is not loaded).
            Unlike restore-epoch / register-epoch-cb (which degrade
            silently), reset-frame-db! cannot — its caller's invariant
            is 'undo works after this call', so absence must be loud."
    (let [hook-key  :epoch/reset-frame-db!
          original  (late-bind/get-fn hook-key)]
      (try
        (late-bind/set-fn! hook-key nil)
        (let [thrown (try (rf/reset-frame-db! :any/frame {})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reset-frame-db! throws when the epoch artefact is absent")
          (is (= ":rf.error/epoch-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'reset-frame-db! (:where data))
                "ex-data carries :where = 'reset-frame-db!")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))
        (finally
          (late-bind/set-fn! hook-key original))))))

;; ---- destroyed-frame contract (rf2-d656) -----------------------------------
;;
;; Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656):
;;   - read-shaped surfaces return empty/nil:
;;       (rf/epoch-history destroyed)  → []
;;       (rf/get-frame-db   destroyed) → nil
;;   - mutate-shaped surfaces raise :rf.error/no-such-handler (kind :frame):
;;       (rf/restore-epoch    destroyed _) → false + :rf.error/no-such-handler
;;       (rf/reset-frame-db!  destroyed _) → false + :rf.error/no-such-handler
;;   - listener silencing emits one-shot :rf.epoch.cb/silenced-on-frame-destroy
;;     when a frame previously observed by a register-epoch-cb callback is
;;     destroyed.

(deftest destroyed-frame-epoch-history-returns-empty
  (testing "(rf/epoch-history frame-id) returns [] for a destroyed frame
            and for a never-registered frame — the read-empty contract"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (is (seq (rf/epoch-history :test/short-lived))
        "before destroy, the frame has at least one recorded epoch")

    (rf/destroy-frame :test/short-lived)
    (is (= [] (rf/epoch-history :test/short-lived))
        "after destroy, epoch-history returns the empty vector")
    (is (= [] (rf/epoch-history :no/such/frame))
        "for a never-registered frame, epoch-history returns the empty vector")))

(deftest destroyed-frame-get-frame-db-returns-nil
  (testing "(rf/get-frame-db frame-id) returns nil for a destroyed frame
            and for a never-registered frame"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (is (some? (rf/get-frame-db :test/short-lived))
        "before destroy, get-frame-db returns the live app-db")

    (rf/destroy-frame :test/short-lived)
    (is (nil? (rf/get-frame-db :test/short-lived))
        "after destroy, get-frame-db returns nil")
    (is (nil? (rf/get-frame-db :no/such/frame))
        "for a never-registered frame, get-frame-db returns nil")))

(deftest destroyed-frame-restore-epoch-raises-no-such-handler
  (testing "(rf/restore-epoch destroyed _) emits :rf.error/no-such-handler
            (kind :frame) and returns false"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (let [eid (-> (rf/epoch-history :test/short-lived) first :epoch-id)]
      (rf/destroy-frame :test/short-lived)
      (let [recorded (record-trace!)
            ok?      (rf/restore-epoch :test/short-lived eid)]
        (is (false? ok?)
            "restore returns false for a destroyed frame")
        (is (has-error-op? @recorded :rf.error/no-such-handler)
            ":rf.error/no-such-handler fired")
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                       @recorded)]
          (is (= :frame (:kind (:tags ev)))
              "tags carry :kind :frame")
          (is (= :test/short-lived (:frame-id (:tags ev)))
              "tags carry :frame-id"))))))

(deftest destroyed-frame-reset-frame-db!-raises-no-such-handler
  (testing "(rf/reset-frame-db! destroyed _) emits :rf.error/no-such-handler
            (kind :frame) and returns false — already covered by
            reset-frame-db!-failure-unknown-frame; this test pins the
            destroyed-frame race specifically (the frame existed, then
            was destroyed)"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (rf/destroy-frame :test/short-lived)

    (let [recorded (record-trace!)
          ok?      (rf/reset-frame-db! :test/short-lived {:n 999})]
      (is (false? ok?)
          "reset-frame-db! returns false for a destroyed frame")
      (is (has-error-op? @recorded :rf.error/no-such-handler)
          ":rf.error/no-such-handler fired")
      (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                     @recorded)]
        (is (= :frame (:kind (:tags ev))))
        (is (= :test/short-lived (:frame-id (:tags ev))))))))

(deftest destroyed-frame-silences-epoch-cb-listener
  (testing "A register-epoch-cb callback that observed a frame receives
            a one-shot :rf.epoch.cb/silenced-on-frame-destroy trace when
            that frame is destroyed. Subsequent destroys of the same
            frame do not re-emit. The callback registration itself
            remains in place."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [received (atom [])
          recorded (record-trace!)]
      (rf/register-epoch-cb ::watcher
                            (fn [r] (swap! received conj r)))
      ;; Drive a cascade so the cb observes the frame.
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (is (= 1 (count @received))
          "the cb received the seed cascade record")
      (is (= :test/short-lived (:frame (first @received)))
          "the observed record was for :test/short-lived")

      ;; Destroy the frame; expect a single silencing trace.
      (rf/destroy-frame :test/short-lived)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 1 (count silenced))
            "exactly one :rf.epoch.cb/silenced-on-frame-destroy fired")
        (let [ev (first silenced)]
          (is (= :rf.epoch.cb (:op-type ev))
              ":op-type is :rf.epoch.cb")
          (is (= :test/short-lived (:frame-id (:tags ev)))
              "tags carry :frame-id")
          (is (= ::watcher (:cb-id (:tags ev)))
              "tags carry :cb-id")))

      ;; The cb is still registered — re-create the frame, drive a
      ;; cascade, the cb fires again.
      (rf/reg-frame :test/short-lived {})
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (is (= 2 (count @received))
          "the same cb continues to fire after the frame is re-registered")

      ;; Destroying again emits a fresh silencing trace (the cb's
      ;; observation set was re-armed when the second cascade landed).
      (rf/destroy-frame :test/short-lived)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 2 (count silenced))
            "a second silencing trace fires for the second destroy")))))

(deftest destroyed-frame-silenced-trace-is-one-shot
  (testing "A repeat destroy of an already-destroyed frame does NOT
            re-emit :rf.epoch.cb/silenced-on-frame-destroy"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [recorded (record-trace!)]
      (rf/register-epoch-cb ::watcher (fn [_] nil))
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (rf/destroy-frame :test/short-lived)

      (let [silenced-after-first (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                             (:operation %))
                                         @recorded)]
        (is (= 1 (count silenced-after-first)))

        ;; The frame is already destroyed; calling destroy again should be
        ;; a no-op (the frame record is gone). Verify no new silencing trace.
        (rf/destroy-frame :test/short-lived)
        (let [silenced-after-second (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                                (:operation %))
                                            @recorded)]
          (is (= 1 (count silenced-after-second))
              "second destroy does NOT re-emit the silencing trace"))))))

(deftest destroyed-frame-silencing-skipped-when-cb-never-observed
  (testing "A register-epoch-cb callback that has never received a record
            for the destroyed frame does NOT receive a silencing trace
            (there is nothing to silence)"
    (rf/reg-frame :test/observed     {})
    (rf/reg-frame :test/never-seen-by-cb {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [recorded (record-trace!)]
      (rf/register-epoch-cb ::watcher (fn [_] nil))
      ;; cb observes :test/observed but NOT :test/never-seen-by-cb
      (rf/dispatch-sync [:seed] {:frame :test/observed})

      ;; Destroy the frame the cb never saw — no silencing trace.
      (rf/destroy-frame :test/never-seen-by-cb)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 0 (count silenced))
            "no silencing trace for a frame the cb never observed"))

      ;; Destroying the cb's observed frame DOES emit silencing.
      (rf/destroy-frame :test/observed)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 1 (count silenced))
            "silencing fires for the observed frame")))))
