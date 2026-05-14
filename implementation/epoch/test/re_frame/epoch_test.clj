(ns re-frame.epoch-test
  "Tool-Pair §Time-travel — epoch recording, query, listener, restore.

  Bead rf2-shjf coverage:

    1. Recording — drain-settle commits a record; multi-event cascades
       commit one record (not one per event).
    2. Per-frame isolation — two frames, each gets its own ring.
    3. Ring depth cap — dispatch >depth events; oldest get evicted.
    4. Configurable depth — `(rf/configure :epoch-history {:depth N})`.
    5. Listener — `register-epoch-cb!` fires per drain-settle with the
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
  ;; Reset the config atom directly so :trace-events-keep (rf2-iegsz)
  ;; doesn't leak between tests — `configure!` merges, so a per-test
  ;; opt-in to elision would otherwise persist.
  (reset! @#'epoch/config {:depth 50})
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
  (testing "register-epoch-cb! fires once per drain-settle with the assembled record"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (let [seen (atom [])]
      (rf/register-epoch-cb! ::watcher (fn [r] (swap! seen conj r)))
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

      (rf/remove-epoch-cb! ::watcher))))

(deftest listener-same-key-replaces
  (testing "register-epoch-cb! under the same key replaces the prior listener"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [a (atom 0)
          b (atom 0)]
      (rf/register-epoch-cb! ::w (fn [_] (swap! a inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (is (= 1 @a))

      (rf/register-epoch-cb! ::w (fn [_] (swap! b inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @a) "the original listener no longer fires after re-register under the same key")
      (is (= 1 @b) "the replacement listener fires"))))

;; ---- rf2-s60jx: multi-listener observed-frames + re-register dissoc ------
;;
;; `notify-listeners!` invokes `record-observation!` once per listener per
;; drain-settle, populating `observed-frames-by-cb[cb-id]` with each
;; frame the cb has seen. Two contracts that weren't pinned:
;;
;;   1. Two listeners both observing the same frame on the same drain
;;      land independent entries in `observed-frames-by-cb` — each cb's
;;      set contains the frame-id.
;;   2. Re-registering a listener under the same id (via
;;      `register-epoch-cb!`) resets BOTH the listener entry AND the
;;      observed-frames entry — so the new callback's silencing trace
;;      fires fresh against frames it observes. The `dissoc` at
;;      epoch.cljc:158 is the non-obvious half of the contract; a
;;      future regression that drops it would leave stale observed-
;;      frames bookkeeping under the new fn's id.

(deftest multi-listener-observed-frames-and-re-register-dissoc
  (testing "two listeners both observing the same frame populate
            independent observed-frames-by-cb entries; re-registering
            under the same id resets BOTH the listener entry and the
            observed-frames entry"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [observed (deref #'epoch/observed-frames-by-cb)
          a        (atom 0)
          b        (atom 0)
          c        (atom 0)]
      ;; Two listeners under independent ids, both observe :test/main.
      (rf/register-epoch-cb! ::w1 (fn [_] (swap! a inc)))
      (rf/register-epoch-cb! ::w2 (fn [_] (swap! b inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @a) "::w1 fired on the cascade")
      (is (= 1 @b) "::w2 fired on the cascade")

      (let [snap @observed]
        (is (contains? (get snap ::w1) :test/main)
            "::w1 has :test/main in its observed-frames")
        (is (contains? (get snap ::w2) :test/main)
            "::w2 has :test/main in its observed-frames")
        (is (= #{:test/main} (get snap ::w1)))
        (is (= #{:test/main} (get snap ::w2))))

      ;; Re-register ::w1 under a different fn — the listener swap is
      ;; well-tested by listener-same-key-replaces. Pin the OTHER half:
      ;; the observed-frames dissoc.
      (rf/register-epoch-cb! ::w1 (fn [_] (swap! c inc)))
      (is (nil? (get @observed ::w1))
          "re-register dissocs the prior observed-frames entry — new
           cb starts with an empty observed-frames set")
      (is (contains? (get @observed ::w2) :test/main)
          "::w2's entry is untouched — re-registration is scoped to ::w1")

      ;; Drive a new cascade — both cbs fire; ::w1's observed-frames
      ;; re-arms with :test/main.
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (is (= 1 @a) "the original ::w1 fn does not fire — it was replaced")
      (is (= 1 @c) "the replacement ::w1 fn fires once")
      (is (= 2 @b) "::w2 keeps firing across both cascades")
      (is (contains? (get @observed ::w1) :test/main)
          "::w1's observed-frames re-armed with :test/main"))))

(deftest listener-remove
  (testing "remove-epoch-cb! stops the listener"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (let [count-a (atom 0)]
      (rf/register-epoch-cb! ::w (fn [_] (swap! count-a inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/remove-epoch-cb! ::w)
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @count-a) "after removal, the listener does not accumulate"))))

(deftest listener-exception-isolation
  (testing "a throwing epoch listener does not crash other listeners or the runtime"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [survivor (atom 0)
          throws   (atom 0)]
      (rf/register-epoch-cb! ::throwing
        (fn [_] (swap! throws inc) (throw (ex-info "tool blew" {}))))
      (rf/register-epoch-cb! ::survivor
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
          (is (= :no/such/frame (:frame (:tags ev)))))))))

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

    ;; Force a sub-run inside a handler (subscribe-once emits :sub/run
    ;; from compute-sub when no cache exists for the query, AND from the
    ;; reactive path when a fresh subscription materialises). Either
    ;; way, the cascade contains :sub/run traces.
    (rf/reg-event-fx :read-sub
      (fn [_ _]
        ;; Read both subs to exercise layer-1 and layer-2.
        (let [_v (rf/subscribe-once :test/main [:n*2])]
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

;; ---- partial-drain semantics (rf2-v0jwt) ---------------------------------

(deftest depth-exceeded-commits-halted-record
  (testing "a depth-exceeded drain commits a :halted-depth epoch record so
            devtools (Causa, re-frame-pair2) receive cascade context for
            the failing cascade. :db-after equals :db-before (atomic
            rollback per Spec 002 §Run-to-completion §Rules rule 3) and
            :halt-reason carries the depth-exceeded descriptor."
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event-fx :loop
      (fn [_ _] {:fx [[:dispatch [:loop]]]}))

    (rf/dispatch-sync [:loop] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 1 (count history))
          "exactly one halted-cascade record committed")
      (let [r (first history)]
        (is (= :halted-depth (:outcome r))
            ":outcome :halted-depth pins the depth-exceed path")
        (is (= (:db-before r) (:db-after r))
            ":db-after equals :db-before — atomic rollback semantics")
        (is (= :rf.error/drain-depth-exceeded
               (-> r :halt-reason :operation))
            ":halt-reason carries the structured halt descriptor")
        (is (= 5 (-> r :halt-reason :depth))
            ":halt-reason carries the depth at which the drain tripped")
        (is (= :loop (:event-id r))
            "the cascade's trigger-event survives in the partial record")
        (is (seq (:trace-events r))
            "the partial record carries the cascade's trace events
             so devtools can render the cascade-up-to-halt")))))

(deftest halted-record-fires-listeners
  (testing "register-epoch-cb! listeners receive halted records too —
            devtools route off :outcome to render failure shapes"
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event-fx :loop
      (fn [_ _] {:fx [[:dispatch [:loop]]]}))

    (let [received (atom [])]
      (rf/register-epoch-cb! ::watcher
                             (fn [record] (swap! received conj record)))
      (rf/dispatch-sync [:loop] {:frame :test/main})

      (is (= 1 (count @received))
          "exactly one record delivered to the listener")
      (is (= :halted-depth (:outcome (first @received)))
          "listener observed the :halted-depth outcome"))))

(deftest restore-non-ok-record-refused
  (testing "restore-epoch refuses non-:ok records — halted records are
            for devtools introspection, not valid restore targets.
            Emits :rf.epoch/restore-non-ok-record and leaves app-db
            unchanged."
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event-fx :loop
      (fn [_ _] {:fx [[:dispatch [:loop]]]}))

    ;; Drive the halted cascade to land a non-:ok record in history.
    (rf/dispatch-sync [:loop] {:frame :test/main})

    (let [history     (rf/epoch-history :test/main)
          halted      (first history)
          recorded    (record-trace!)
          result      (rf/restore-epoch :test/main (:epoch-id halted))]
      (is (= :halted-depth (:outcome halted))
          "sanity — the only record in history is the halted one")
      (is (false? result)
          "restore-epoch returned false — refusal is observable to callers")
      (is (has-error-op? @recorded :rf.epoch/restore-non-ok-record)
          ":rf.epoch/restore-non-ok-record fired so listeners can surface
           the refusal to the user"))))

;; ---- recording is gated on debug-enabled? ---------------------------------

(deftest configure-roundtrip
  (testing "(rf/configure :epoch-history {:depth N}) updates the depth"
    (rf/configure :epoch-history {:depth 7})
    (is (= 7 (:depth (epoch/current-config))))
    (rf/configure :epoch-history {:depth 12})
    (is (= 12 (:depth (epoch/current-config))))))

;; ---- rf2-iegsz: :trace-events elision policy ------------------------------
;;
;; Per Spec-Schemas §`:rf/epoch-record` line 2224, `:trace-events` is
;; optional — 'implementations may choose to drop traces from older
;; epochs'. The default (pre-rf2-iegsz, also the absent-config default)
;; keeps every record's `:trace-events`. The `:trace-events-keep N` knob
;; bounds the per-frame trace-event memory to the most-recent N records;
;; older records keep their cheap structured projections (`:sub-runs` /
;; `:renders` / `:effects`) but lose the raw trace stream.

(deftest trace-events-keep-elides-older-records
  (testing "with :trace-events-keep N set, only the most-recent N records
            carry :trace-events; older records keep :sub-runs / :renders /
            :effects but drop :trace-events"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (rf/configure :epoch-history {:depth 10 :trace-events-keep 2})

    ;; Drive 5 cascades so the buffer has 5 records and only the last 2
    ;; should retain :trace-events.
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 5 (count history))
          "all 5 records remain in the ring (depth 10)")
      (is (every? #(contains? % :sub-runs) history)
          "every record keeps its :sub-runs projection")
      (is (every? #(contains? % :effects) history)
          "every record keeps its :effects projection")
      (is (every? #(contains? % :renders) history)
          "every record keeps its :renders projection")

      (let [[r0 r1 r2 r3 r4] history]
        (is (not (contains? r0 :trace-events))
            "record 0 (oldest) — :trace-events dropped")
        (is (not (contains? r1 :trace-events))
            "record 1 — :trace-events dropped")
        (is (not (contains? r2 :trace-events))
            "record 2 — :trace-events dropped")
        (is (contains? r3 :trace-events)
            "record 3 — :trace-events kept (penultimate)")
        (is (contains? r4 :trace-events)
            "record 4 — :trace-events kept (most-recent)")))))

(deftest trace-events-keep-absent-keeps-all-trace-events
  (testing "absent :trace-events-keep — every record carries :trace-events
            (default behaviour preserved)"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    ;; Default config — no :trace-events-keep set.
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 3 (count history)))
      (is (every? #(contains? % :trace-events) history)
          "every record carries :trace-events — no elision applied"))))

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
;; (no cache), so subscribe-once before/after restore is a clean read of
;; the post-restore container. The CLJS Reagent counterpart in
;; runtime_cljs_test.cljs covers the reactive-graph case where a held
;; reaction must observe the rewound value.

(deftest restore-rewinds-subscriptions-via-subscribe-once
  (testing "after restore-epoch, subscribe-once reflects the restored db
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

    (is (= 3 (rf/subscribe-once :test/main [:n])))
    (is (= 6 (rf/subscribe-once :test/main [:n*2])))

    (let [history (rf/epoch-history :test/main)
          ;; Pick the epoch where :n landed at 1 (second :inc dispatch).
          target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) history)]
      (is (true? (rf/restore-epoch :test/main (:epoch-id target))))
      (is (= 1 (rf/subscribe-once :test/main [:n]))
          "layer-1 sub now sees the restored value (no manual invalidation)")
      (is (= 2 (rf/subscribe-once :test/main [:n*2]))
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
      (is (= 1   (rf/subscribe-once :frame/a [:n]))
          "frame A's sub sees the rewound value")
      (is (= 101 (rf/subscribe-once :frame/b [:n]))
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
            sub-1      (rf/subscribe-once :test/main [:n])]
        (is (= {:n 1} db-after-1))
        (is (= 1     sub-1))

        ;; Restore again to the SAME epoch.
        (is (true? (rf/restore-epoch :test/main target-eid)) "second restore ok")
        (is (= db-after-1 (rf/get-frame-db :test/main))
            "app-db unchanged across the second restore")
        (is (= sub-1 (rf/subscribe-once :test/main [:n]))
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
    (is (= :route/article (rf/subscribe-once :test/main [:current-route])))

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
      (is (= :route/home (rf/subscribe-once :test/main [:current-route]))
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
;; 6. Listeners: register-epoch-cb! fires with the assembled record.
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
            register-epoch-cb! listeners"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [received (atom [])]
      (rf/register-epoch-cb! ::reset-listener
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
          (rf/remove-epoch-cb! ::reset-listener))))))

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
        (is (= :no/such/frame (:frame (:tags ev))))))))

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
    (is (= 0 (rf/subscribe-once :test/main [:n])))
    (is (= 0 (rf/subscribe-once :test/main [:n*2])))

    (rf/reset-frame-db! :test/main {:n 21})
    (is (= 21 (rf/subscribe-once :test/main [:n]))
        "layer-1 sub returns the post-reset value")
    (is (= 42 (rf/subscribe-once :test/main [:n*2]))
        "derived sub re-computes against the post-reset value")))

(deftest reset-frame-db!-raises-when-epoch-artefact-missing
  (testing "Per the rf2-5b6x missing-artefact error contract:
            rf/reset-frame-db! raises :rf.error/epoch-artefact-missing
            when the :epoch/reset-frame-db! late-bind hook is nil
            (i.e. the day8/re-frame2-epoch artefact is not loaded).
            Unlike restore-epoch / register-epoch-cb! (which degrade
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
            (is (= 'rf/reset-frame-db! (:where data))
                "ex-data carries :where = 'rf/reset-frame-db!")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))
        (finally
          (late-bind/set-fn! hook-key original))))))

;; ---- capture-event! skip-ops cross-contamination (rf2-htf28) ---------------
;;
;; Every `:rf.epoch/*` op this namespace emits with a `:frame` tag fires
;; OUTSIDE a cascade (the drain has either not started, or has just
;; settled and the buffer has been harvested). If `capture-event!`
;; failed to skip them they would accrete into `capture-buffers` and
;; leak into the NEXT cascade's harvested record for the same frame —
;; phantom `:trace-events` and a wrong `:trigger-event` from
;; `find-trigger-event`'s fallback arm.
;;
;; This catalogue test pins the `skip-ops` set against every
;; `:rf.epoch/*` op the namespace emits. If a future op is added (e.g.
;; an in-drain `:rf.epoch/cascade-rollback`) and forgotten in
;; `skip-ops`, OR a stale op is left there, the diff between
;; observed-and-skipped ops vs the registry will tell. Keeps the
;; deliberate-enumeration choice right-by-construction.

(deftest reset-frame-db-does-not-leak-into-next-cascade
  (testing "after reset-frame-db! on a frame, the NEXT cascade on that
            frame harvests a record whose :trace-events excludes the
            out-of-drain :rf.epoch/db-replaced emit, and whose
            :trigger-event is the actual next dispatched event (NOT
            [:rf.epoch/db-replaced] picked from a leaked buffer)"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))

    ;; Cascade 1: a real event, lands a clean record.
    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; Out-of-drain emit: :rf.epoch/db-replaced fires with a :frame tag.
    ;; Pre-fix, capture-event! would buffer it into capture-buffers[:test/main].
    (rf/reset-frame-db! :test/main {:n 100})

    ;; Cascade 2: a real event. Post-fix, its harvested record reflects
    ;; only the :bump cascade. Pre-fix, the leaked :rf.epoch/db-replaced
    ;; event would be the FIRST event in the buffer, and
    ;; find-trigger-event's fallback arm would pick its :epoch-id over
    ;; the real :bump event.
    (rf/dispatch-sync [:bump] {:frame :test/main})

    (let [history    (rf/epoch-history :test/main)
          ;; Skip the :rf.epoch/db-replaced synthetic record itself —
          ;; we're checking the cascade that ran AFTER it.
          post-reset (last history)]
      (is (= :bump (:event-id post-reset))
          ":event-id is the real cascade trigger, not :rf.epoch/db-replaced")
      (is (= [:bump] (:trigger-event post-reset))
          ":trigger-event is the real event vector, not the leaked sentinel")
      (is (not-any? (fn [ev] (= :rf.epoch/db-replaced (:operation ev)))
                    (:trace-events post-reset))
          ":trace-events does NOT contain the out-of-drain :rf.epoch/db-replaced emit")
      ;; project-effects has no fx in :bump, but project-sub-runs /
      ;; project-renders walking a leaked event with op :rf.epoch/db-replaced
      ;; would silently be empty anyway — the strong signal is the trigger-
      ;; event check above plus the trace-events absence.
      (is (empty? (:effects post-reset))
          "no leaked effects from the out-of-drain emit"))))

(deftest reset-frame-db-failure-does-not-leak-into-next-cascade
  (testing "the two reset-frame-db! failure-mode emits
            (:rf.epoch/reset-frame-db-during-drain,
             :rf.epoch/reset-frame-db-schema-mismatch) fire outside a
            cascade with :frame tags. They MUST be filtered out of
            capture-event!'s buffering — otherwise a failed
            reset-frame-db! attempt leaks a phantom event into the next
            real cascade for that frame."
    ;; Use the schema-mismatch path — easier to drive than during-drain.
    (rf/reg-frame :test/sm {})
    (rf/reg-app-schema [:n] [:int] {:frame :test/sm})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:seed] {:frame :test/sm})

    ;; This fails — new-db doesn't validate. Emits
    ;; :rf.epoch/reset-frame-db-schema-mismatch with :frame :test/sm.
    (is (false? (rf/reset-frame-db! :test/sm {:n "not-an-int"})))

    ;; Next cascade — should NOT carry the failure emit.
    (rf/dispatch-sync [:bump] {:frame :test/sm})

    (let [post-fail (last (rf/epoch-history :test/sm))]
      (is (= :bump (:event-id post-fail)))
      (is (= [:bump] (:trigger-event post-fail)))
      (is (not-any? (fn [ev]
                      (= :rf.epoch/reset-frame-db-schema-mismatch
                         (:operation ev)))
                    (:trace-events post-fail))
          "failure-mode emit is filtered from the next cascade's trace stream"))))

(deftest restore-epoch-emits-do-not-leak-into-next-cascade
  (testing "restore-epoch's success emit (:rf.epoch/restored) and its
            five documented failure-mode emits all fire outside a
            cascade with :frame tags. None may bleed into the next
            real cascade's :trace-events for that frame."
    (rf/reg-frame :test/r {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:seed] {:frame :test/r})
    (rf/dispatch-sync [:bump] {:frame :test/r})
    (let [seed-epoch (first (rf/epoch-history :test/r))]
      ;; Successful restore — emits :rf.epoch/restored out-of-drain.
      (is (true? (rf/restore-epoch :test/r (:epoch-id seed-epoch))))
      ;; Failed restore — unknown epoch-id emits
      ;; :rf.epoch/restore-unknown-epoch out-of-drain.
      (is (false? (rf/restore-epoch :test/r 999999)))

      ;; Next cascade — should NOT carry either emit.
      (rf/dispatch-sync [:bump] {:frame :test/r})
      (let [post (last (rf/epoch-history :test/r))
            ops  (into #{} (map :operation (:trace-events post)))]
        (is (= :bump (:event-id post)))
        (is (= [:bump] (:trigger-event post)))
        (is (not (contains? ops :rf.epoch/restored))
            ":rf.epoch/restored does not leak from a prior successful restore")
        (is (not (contains? ops :rf.epoch/restore-unknown-epoch))
            ":rf.epoch/restore-unknown-epoch does not leak from a prior failed restore")))))

(deftest skip-ops-catalogue-pins-every-rf-epoch-op
  (testing "skip-ops covers every :rf.epoch/* op this namespace emits
            with a :frame tag (catalogue test — adds a fail-loudly
            signal if a new in-namespace :rf.epoch/* op is introduced
            and forgotten in `skip-ops`)"
    ;; The exhaustive set as of rf2-htf28. Update both this catalogue
    ;; AND `skip-ops` when adding/removing an op the namespace emits.
    ;; (`:rf.epoch.cb/silenced-on-frame-destroy` is op-type :rf.epoch.cb,
    ;; not :rf.epoch — and it emits AFTER the frame's ring buffer has
    ;; been dropped so it can't race a future cascade. Not in skip-ops.)
    (let [expected #{:rf.epoch/snapshotted
                     :rf.epoch/restored
                     :rf.epoch/restore-unknown-epoch
                     :rf.epoch/restore-schema-mismatch
                     :rf.epoch/restore-missing-handler
                     :rf.epoch/restore-version-mismatch
                     :rf.epoch/restore-during-drain
                     :rf.epoch/restore-non-ok-record    ;; rf2-v0jwt
                     :rf.epoch/db-replaced
                     :rf.epoch/reset-frame-db-during-drain
                     :rf.epoch/reset-frame-db-schema-mismatch}
          actual   @#'epoch/skip-ops]
      (is (= expected actual)
          "skip-ops catalogue matches the documented set of
           out-of-cascade :rf.epoch/* emits"))))

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
;;     when a frame previously observed by a register-epoch-cb! callback is
;;     destroyed.

(deftest destroyed-frame-epoch-history-returns-empty
  (testing "(rf/epoch-history frame-id) returns [] for a destroyed frame
            and for a never-registered frame — the read-empty contract"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (is (seq (rf/epoch-history :test/short-lived))
        "before destroy, the frame has at least one recorded epoch")

    (rf/destroy-frame! :test/short-lived)
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

    (rf/destroy-frame! :test/short-lived)
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
      (rf/destroy-frame! :test/short-lived)
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
          (is (= :test/short-lived (:frame (:tags ev)))
              "tags carry :frame"))))))

(deftest destroyed-frame-reset-frame-db!-raises-no-such-handler
  (testing "(rf/reset-frame-db! destroyed _) emits :rf.error/no-such-handler
            (kind :frame) and returns false — already covered by
            reset-frame-db!-failure-unknown-frame; this test pins the
            destroyed-frame race specifically (the frame existed, then
            was destroyed)"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (rf/destroy-frame! :test/short-lived)

    (let [recorded (record-trace!)
          ok?      (rf/reset-frame-db! :test/short-lived {:n 999})]
      (is (false? ok?)
          "reset-frame-db! returns false for a destroyed frame")
      (is (has-error-op? @recorded :rf.error/no-such-handler)
          ":rf.error/no-such-handler fired")
      (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                     @recorded)]
        (is (= :frame (:kind (:tags ev))))
        (is (= :test/short-lived (:frame (:tags ev))))))))

(deftest destroyed-frame-silences-epoch-cb-listener
  (testing "A register-epoch-cb! callback that observed a frame receives
            a one-shot :rf.epoch.cb/silenced-on-frame-destroy trace when
            that frame is destroyed. Subsequent destroys of the same
            frame do not re-emit. The callback registration itself
            remains in place."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [received (atom [])
          recorded (record-trace!)]
      (rf/register-epoch-cb! ::watcher
                            (fn [r] (swap! received conj r)))
      ;; Drive a cascade so the cb observes the frame.
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (is (= 1 (count @received))
          "the cb received the seed cascade record")
      (is (= :test/short-lived (:frame (first @received)))
          "the observed record was for :test/short-lived")

      ;; Destroy the frame; expect a single silencing trace.
      (rf/destroy-frame! :test/short-lived)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 1 (count silenced))
            "exactly one :rf.epoch.cb/silenced-on-frame-destroy fired")
        (let [ev (first silenced)]
          (is (= :rf.epoch.cb (:op-type ev))
              ":op-type is :rf.epoch.cb")
          (is (= :test/short-lived (:frame (:tags ev)))
              "tags carry :frame")
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
      (rf/destroy-frame! :test/short-lived)
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
      (rf/register-epoch-cb! ::watcher (fn [_] nil))
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (rf/destroy-frame! :test/short-lived)

      (let [silenced-after-first (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                             (:operation %))
                                         @recorded)]
        (is (= 1 (count silenced-after-first)))

        ;; The frame is already destroyed; calling destroy again should be
        ;; a no-op (the frame record is gone). Verify no new silencing trace.
        (rf/destroy-frame! :test/short-lived)
        (let [silenced-after-second (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                                (:operation %))
                                            @recorded)]
          (is (= 1 (count silenced-after-second))
              "second destroy does NOT re-emit the silencing trace"))))))

(deftest destroyed-frame-silencing-skipped-when-cb-never-observed
  (testing "A register-epoch-cb! callback that has never received a record
            for the destroyed frame does NOT receive a silencing trace
            (there is nothing to silence)"
    (rf/reg-frame :test/observed     {})
    (rf/reg-frame :test/never-seen-by-cb {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))

    (let [recorded (record-trace!)]
      (rf/register-epoch-cb! ::watcher (fn [_] nil))
      ;; cb observes :test/observed but NOT :test/never-seen-by-cb
      (rf/dispatch-sync [:seed] {:frame :test/observed})

      ;; Destroy the frame the cb never saw — no silencing trace.
      (rf/destroy-frame! :test/never-seen-by-cb)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 0 (count silenced))
            "no silencing trace for a frame the cb never observed"))

      ;; Destroying the cb's observed frame DOES emit silencing.
      (rf/destroy-frame! :test/observed)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 1 (count silenced))
            "silencing fires for the observed frame")))))

;; ---- rf2-jvrd: clear-frame-history! seam pin ------------------------------
;;
;; Per test-coverage-review-2026-05-12 P3-19. Public seam at
;; `epoch.cljc:111`; covered transitively by `clear-history!` but no test
;; pins the single-frame variant directly.

(deftest clear-frame-history-isolates-to-the-named-frame
  (testing "clear-frame-history! drops one frame's ring; other frames'
            rings are untouched; calling against an unknown frame is a
            silent no-op"
    (rf/reg-frame :test/main  {})
    (rf/reg-frame :test/other {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    ;; Drain several epochs on each frame.
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:seed] {:frame :test/other})
    (rf/dispatch-sync [:inc]  {:frame :test/other})

    ;; Sanity: both rings carry records.
    (is (= 3 (count (rf/epoch-history :test/main))))
    (is (= 2 (count (rf/epoch-history :test/other))))

    ;; Clear only :test/other. Per rf2-sh5g6 the seam is `defn-` (no
    ;; late-bind hook) so the test reaches it via the private-var
    ;; access form.
    (is (nil? (#'epoch/clear-frame-history! :test/other))
        "clear-frame-history! returns nil")

    ;; :test/other is empty; :test/main untouched.
    (is (= [] (rf/epoch-history :test/other))
        ":test/other's ring is empty after clear")
    (is (= 3 (count (rf/epoch-history :test/main)))
        ":test/main's ring is unchanged — clear-frame-history! is scoped")

    ;; Negative: clear-frame-history! against an unknown frame is a no-op
    ;; (does not throw, does not affect other rings).
    (is (nil? (#'epoch/clear-frame-history! :test/no-such-frame))
        "clear-frame-history! on an unknown frame is a silent no-op")
    (is (= 3 (count (rf/epoch-history :test/main)))
        ":test/main's ring is still untouched after a no-op clear")
    (is (= [] (rf/epoch-history :test/other))
        ":test/other's ring stays empty (no re-population)")))

;; ---- rf2-ronz: on-frame-destroyed! direct unit pin ------------------------
;;
;; Per test-coverage-review-2026-05-12 P3-20. Currently reached only
;; via destroyed-frame-epoch-history-returns-empty and
;; destroyed-frame-get-frame-db-returns-nil; no direct unit pins the
;; contract. on-frame-destroyed! is the late-bind hook
;; (`re-frame.frame/destroy-frame!` calls it via `:epoch/on-frame-destroyed`).
;; Tools and alternate-destroy paths invoke it directly; pin the
;; seam.

(deftest on-frame-destroyed-clears-frame-buffer-directly
  (testing "calling epoch/on-frame-destroyed! on a frame drops its
            ring buffer regardless of whether the frame itself was
            destroyed via the usual frame/destroy-frame! path"
    (rf/reg-frame :test/other {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    ;; Populate :test/other's ring buffer.
    (rf/dispatch-sync [:seed] {:frame :test/other})
    (rf/dispatch-sync [:inc]  {:frame :test/other})
    (rf/dispatch-sync [:inc]  {:frame :test/other})
    (is (= 3 (count (rf/epoch-history :test/other)))
        ":test/other's ring is populated before the seam call")

    ;; Call on-frame-destroyed! DIRECTLY — without going through
    ;; frame/destroy-frame!. The frame record still exists in
    ;; frames-atom; only the epoch ring is dropped.
    (epoch/on-frame-destroyed! :test/other)

    ;; The frame's ring is gone.
    (is (= [] (rf/epoch-history :test/other))
        "epoch-history returns the empty vector after on-frame-destroyed!")

    ;; The frame record itself is still queryable — on-frame-destroyed! is
    ;; scoped to epoch-internal state.
    (is (some? (frame/frame :test/other))
        "the frame is still registered (we did NOT call destroy-frame!)")))

(deftest on-frame-destroyed-idempotent-on-repeat
  (testing "on-frame-destroyed! is idempotent — repeated calls on the
            same frame are no-ops (no throw, no side-effect cascade)"
    (rf/reg-frame :test/repeat {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/repeat})
    (is (= 1 (count (rf/epoch-history :test/repeat))))

    ;; First call — clears the buffer.
    (epoch/on-frame-destroyed! :test/repeat)
    (is (= [] (rf/epoch-history :test/repeat)))

    ;; Second call — no-op.
    (epoch/on-frame-destroyed! :test/repeat)
    (is (= [] (rf/epoch-history :test/repeat))
        "ring stays empty across repeated calls")))

(deftest on-frame-destroyed-on-unknown-frame-is-noop
  (testing "on-frame-destroyed! on a frame that was never registered does
            not throw — it's a clean no-op"
    ;; on-frame-destroyed! is a side-effect fn over the internal
    ;; epoch-state atoms; its return value is whatever swap! produces.
    ;; The observable contract is "no throw, no side effects on
    ;; unrelated state".
    (let [traces (record-trace!)]
      (epoch/on-frame-destroyed! :test/no-such-frame)
      (is (empty? @traces)
          "no traces emitted — nothing observed to silence"))))

;; ---- rf2-5qbus: capture-buffer cross-contamination from out-of-drain emits ---
;;
;; The `capture-event!` fn must skip every `:rf.epoch/*` op the namespace
;; emits OUTSIDE a cascade (catalogued in the `skip-ops` set). Without
;; the skip, a `reset-frame-db!` call (which emits `:rf.epoch/db-replaced`
;; after harvesting the cascade buffer) would buffer the db-replaced
;; trace event into `capture-buffers[frame-id]`, and the NEXT cascade
;; for the same frame would harvest it as the first event in the
;; buffer — treating it as belonging to that cascade. The
;; `find-trigger-event` fallback would pick its `:epoch-id` as the
;; trigger; `project-effects`/`project-sub-runs` would silently include
;; it. The rf2-htf28 fix added the missing ops to the skip-set; pin
;; the contract here so a future regression that drops them surfaces
;; loudly.

(deftest capture-buffer-does-not-cross-contaminate-from-reset-frame-db
  (testing "an out-of-drain :rf.epoch/db-replaced emit from
            reset-frame-db! does NOT leak into the next cascade's
            harvested record for the same frame"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :foo  (fn [db _] (assoc db :foo? true)))

    ;; 1. Drive one cascade — clean record lands.
    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; 2. reset-frame-db! out-of-drain — emits :rf.epoch/db-replaced
    ;;    which (pre-rf2-htf28) would buffer into capture-buffers.
    (is (true? (rf/reset-frame-db! :test/main {:n 99})))

    ;; 3. Drive a second cascade for [:foo].
    (rf/dispatch-sync [:foo] {:frame :test/main})

    (let [history    (rf/epoch-history :test/main)
          last-record (peek history)]
      ;; The history has 3 records: [:seed], the synthetic
      ;; reset-frame-db! record, and [:foo].
      (is (= 3 (count history)))

      ;; The most-recent record (the [:foo] cascade) must be clean —
      ;; its trigger-event is [:foo], its trace stream carries no
      ;; :rf.epoch/db-replaced events.
      (is (= [:foo] (:trigger-event last-record))
          "last record's :trigger-event is [:foo] — not a phantom
           :rf.epoch/db-replaced")
      (is (= :foo (:event-id last-record))
          "last record's :event-id is :foo")
      (is (not-any? #(= :rf.epoch/db-replaced (:operation %))
                    (:trace-events last-record))
          "no :rf.epoch/db-replaced trace leaked into the cascade's
           harvested trace-events"))))

;; ---- rf2-zzper: on-frame-destroyed! drops in-flight capture-buffer --------
;;
;; The router calls `discard-buffer!` for the routine cascade-abort case
;; (depth-exceeded, dispatch-sync rejection, etc.). But a destroy that
;; races a mid-flight drain — e.g. a hot-reload firing while the drain
;; has buffered events but has not yet settled — would otherwise leave
;; a stale partial buffer hanging on `capture-buffers[frame-id]`. The
;; next cascade against a same-keyed frame would harvest those
;; pre-destroy events as belonging to its first record. Pin the
;; contract: `on-frame-destroyed!` clears the capture-buffer entry
;; symmetric to the ring-buffer drop.

(deftest on-frame-destroyed-drops-in-flight-capture-buffer
  (testing "on-frame-destroyed! drops :capture-buffers[frame-id] so a
            mid-drain destroy can't leak pre-destroy events into the
            first cascade of the next same-keyed frame"
    (rf/reg-frame :test/main {})

    ;; Synthesize a mid-flight capture-buffer entry directly. The
    ;; private capture-buffers atom holds frame-id → vector of trace
    ;; events that were buffered between drain-start and drain-settle.
    ;; A real-world race would have at least one event in this vector
    ;; when the destroy lands; pin the contract by inserting a
    ;; synthetic entry.
    ;;
    ;; (`rf/reg-frame` above emits a `:frame/created` trace which
    ;; capture-event! buffers since the tag carries `:frame`. Reset
    ;; explicitly so the test starts from a known-empty buffer
    ;; rather than relying on the reg-frame side-effect.)
    (let [buffers-atom @#'epoch/capture-buffers]
      (reset! buffers-atom {})

      (swap! buffers-atom assoc :test/main
             [{:op-type   :event
               :operation :event
               :tags      {:event    [:pre-destroy]
                           :event-id :pre-destroy
                           :frame    :test/main
                           :phase    :run-start}}])

      (is (some? (get @buffers-atom :test/main))
          "sanity: the synthetic capture-buffer entry is present pre-destroy")

      (epoch/on-frame-destroyed! :test/main)

      (is (nil? (get @buffers-atom :test/main))
          "the capture-buffer entry was dropped on destroy — no
           pre-destroy event can leak into a same-keyed frame's next
           cascade"))))

;; ---- rf2-kl5p1: build-record omits :event-id / :trigger-event when
;; ---- find-trigger-event yields nothing -----------------------------------
;;
;; Per audit r3 §F1: `:rf/epoch-record` declares `[:event-id :keyword]`
;; (required, non-maybe per Spec-Schemas §`:rf/epoch-record`). The live
;; router halt paths short-circuit `build-record` on an empty buffer via
;; `(when (seq events) ...)` in `settle!`, but `on-frame-destroyed!`'s
;; `:halted-destroy` path commits a partial record from whatever buffered
;; events are present — and a degenerate buffer that holds a `:event/
;; run-start` but no `:event-id` / `:event` tags would otherwise produce
;; `:event-id nil` / `:trigger-event nil`, violating the schema.
;;
;; Contract pin: when `find-trigger-event` returns nil for both fields,
;; the assembled record carries NEITHER slot (the schema admits the
;; absent slot, rejects nil values). `build-record` is exercised
;; directly because driving a real router path with this exact degenerate
;; buffer requires cooperation with internals the audit-time fix does
;; not change.

(deftest build-record-omits-event-id-and-trigger-event-on-tag-less-buffer
  (testing "build-record on a buffer whose only `:event/run-start` trace
            carries neither :event-id nor :event in :tags omits the
            :event-id and :trigger-event slots from the record (rather
            than emitting them as nil, which would violate the
            :event-id :keyword schema)"
    (rf/reg-frame :test/main {})
    ;; Synthetic `:event/run-start` with empty tags — the `in-cascade?`
    ;; gate at on-frame-destroyed! fires on phase :run-start, but the
    ;; tags carry no :event-id / :event, so find-trigger-event resolves
    ;; nothing. This mirrors the degenerate path the audit identified
    ;; on the :halted-destroy commit.
    (let [tag-less-events [{:op-type   :event
                            :operation :event
                            :tags      {:frame :test/main
                                        :phase :run-start}}]
          record          (#'epoch/build-record
                            :test/main nil nil tag-less-events
                            :halted-destroy
                            {:operation :rf.frame/destroyed-mid-drain})]
      (is (not (contains? record :event-id))
          "the record does NOT carry :event-id when find-trigger-event
           yields nil — the slot is absent rather than nil-valued
           (schema rejects nil; absent is fine on the open map)")
      (is (not (contains? record :trigger-event))
          "the record does NOT carry :trigger-event when find-trigger-event
           yields nil — symmetric to :event-id, the slot is absent
           rather than nil-valued")
      ;; Sanity: every required non-conditional slot still landed, and
      ;; the halt-reason came through.
      (is (= :test/main (:frame record)))
      (is (= :halted-destroy (:outcome record)))
      (is (= {:operation :rf.frame/destroyed-mid-drain}
             (:halt-reason record))))))

(deftest build-record-emits-event-id-and-trigger-event-when-trigger-resolves
  (testing "the conditional cond-> slots are emitted when find-trigger-event
            resolves both — the rf2-kl5p1 fix must not regress the
            happy-path record shape"
    (rf/reg-frame :test/main {})
    (let [events [{:op-type   :event
                   :operation :event
                   :tags      {:frame    :test/main
                               :phase    :run-start
                               :event-id :seed
                               :event    [:seed 1 2 3]}}]
          record (#'epoch/build-record :test/main {} {:n 0} events)]
      (is (= :seed (:event-id record))
          ":event-id is the resolved event keyword")
      (is (= [:seed 1 2 3] (:trigger-event record))
          ":trigger-event is the full event vector — payload preserved"))))

;; ---- rf2-7kxxx: find-trigger-event must not synthesise [eid] when
;; ---- :event tag is absent on the fallback arm ----------------------------
;;
;; Per audit r3 §F2: the fallback arm of `find-trigger-event` previously
;; synthesised `[eid]` as `:event` when the buffered event carried an
;; `:event-id` tag but no `:event` tag. That misrepresents an event that
;; originally carried payload (e.g. `[:foo "bar" 42]`) as a payload-less
;; event. Post-fix: the fallback returns `:event nil` when the tag is
;; absent, and (per rf2-kl5p1) `build-record` then omits the
;; `:trigger-event` slot entirely. No silent fabrication; consumers
;; rendering 'what triggered this cascade' either see the real event
;; vector or none at all.

(deftest find-trigger-event-fallback-does-not-synthesise-event-vector
  (testing "find-trigger-event's fallback arm — :event-id tag present,
            :event tag absent — returns :event nil rather than fabricating
            [eid]; the calling build-record then omits :trigger-event"
    (let [tag-less-fallback [{:op-type   :event
                              :operation :event
                              :tags      {:frame    :test/main
                                          :event-id :foo}}]
          trigger           (#'epoch/find-trigger-event tag-less-fallback)]
      (is (= :foo (:event-id trigger))
          ":event-id is recovered from the fallback arm")
      (is (nil? (:event trigger))
          ":event is NOT synthesised as [:foo] — the slot is nil so
           build-record can decide not to emit a fabricated
           :trigger-event"))

    ;; Build-record consumes the fallback's nil :event via its conditional
    ;; cond-> (rf2-kl5p1) and emits no :trigger-event slot.
    (rf/reg-frame :test/main {})
    (let [tag-less-events [{:op-type   :event
                            :operation :event
                            :tags      {:frame    :test/main
                                        :event-id :foo}}]
          record          (#'epoch/build-record
                            :test/main nil nil tag-less-events
                            :halted-destroy
                            {:operation :rf.frame/destroyed-mid-drain})]
      (is (= :foo (:event-id record))
          ":event-id lands on the record from the fallback arm")
      (is (not (contains? record :trigger-event))
          ":trigger-event is absent — no synthesised vector survives
           into the record"))))

(deftest find-trigger-event-fallback-preserves-payload-when-event-tag-present
  (testing "find-trigger-event's fallback arm preserves the full event
            vector when the buffered event DOES carry an :event tag —
            the rf2-7kxxx fix must not strip payload from the
            non-degenerate fallback path"
    (let [events  [{:op-type   :event
                    :operation :event
                    :tags      {:frame    :test/main
                                :event-id :foo
                                :event    [:foo "bar" 42]}}]
          trigger (#'epoch/find-trigger-event events)]
      (is (= :foo (:event-id trigger)))
      (is (= [:foo "bar" 42] (:event trigger))
          "the full event vector survives — payload is preserved"))))

;; ---- rf2-eo4pr: record-observation! guards its swap -----------------------
;;
;; `notify-listeners!` invokes `record-observation!` once per listener per
;; drain-settle. For the steady state — a long-lived listener observing the
;; same frame on every cascade — the cb's observed-frames set already
;; contains the frame-id, and an unconditional `swap!` would fire every
;; atom watcher N times per settle for ZERO semantic change. The guard
;; inside `record-observation!` short-circuits the already-observed case;
;; this test pins that no-op via an atom watcher (the canonical witness:
;; Clojure's persistent-map `assoc`/`update` can return an identical map
;; when the value is unchanged, so identity-equality alone is too weak,
;; but `add-watch` ALWAYS fires on every successful `swap!`).

(deftest record-observation-no-op-when-already-observed
  (testing "record-observation! does NOT swap! the observed-frames-by-cb
            atom when the (cb-id, frame-id) pair is already present —
            repeated drain-settles for a stable listener-observing-frame
            pairing leave the atom untouched (no watcher fires)"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (let [observed-atom @#'epoch/observed-frames-by-cb
          swap-count    (atom 0)]
      (add-watch observed-atom ::swap-counter
                 (fn [_ _ _ _] (swap! swap-count inc)))
      (try
        ;; First cascade — the cb observes :test/main for the first time;
        ;; the membership is added. One swap is expected here (the new
        ;; observation lands on the atom).
        (rf/register-epoch-cb! ::watcher (fn [_] nil))
        (rf/dispatch-sync [:seed] {:frame :test/main})
        (is (contains? (get @observed-atom ::watcher) :test/main)
            "after the first cascade, the cb has :test/main in its set")

        (let [swaps-after-first @swap-count]
          (is (pos? swaps-after-first)
              "the first observation triggered at least one swap")

          ;; Drive more cascades — every one is a repeat observation of
          ;; the same (cb-id, frame-id) pair. The guard must short-circuit
          ;; so no further swap fires.
          (rf/dispatch-sync [:inc] {:frame :test/main})
          (rf/dispatch-sync [:inc] {:frame :test/main})
          (rf/dispatch-sync [:inc] {:frame :test/main})

          (is (= swaps-after-first @swap-count)
              "no further swap! ran across the three repeat cascades —
               record-observation! short-circuited on the already-observed
               case")
          (is (= 1 (count (get @observed-atom ::watcher)))
              "the cb's observed-frames set still has exactly one entry")
          (is (= 4 (count (rf/epoch-history :test/main)))
              "the four cascades did land four epoch records — the cb was
               notified each time, but the observation atom stayed put"))
        (finally
          (remove-watch observed-atom ::swap-counter))))))

;; ---- rf2-douii: configure! validates at the boundary ---------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: `configure!` previously
;; accepted any value for `:depth` and `:trace-events-keep`. A nil or
;; non-numeric value would survive configuration and explode later at
;; `record!` time when `pos?` / `nat-int?` ran on the stored value. The
;; validation now sanitises at the boundary — invalid values are silently
;; dropped, the prior valid config survives.

(deftest configure-rejects-nil-depth
  (testing "(rf/configure :epoch-history {:depth nil}) is a no-op; the
            previously-stored depth survives"
    (rf/configure :epoch-history {:depth 7})
    (is (= 7 (:depth (epoch/current-config))))

    (rf/configure :epoch-history {:depth nil})
    (is (= 7 (:depth (epoch/current-config)))
        ":depth nil silently dropped — prior 7 survives")))

(deftest configure-rejects-non-numeric-depth
  (testing "(rf/configure :epoch-history {:depth \"five\"}) is a no-op"
    (rf/configure :epoch-history {:depth 7})
    (rf/configure :epoch-history {:depth "five"})
    (is (= 7 (:depth (epoch/current-config)))
        ":depth non-numeric silently dropped")))

(deftest configure-rejects-negative-depth
  (testing "(rf/configure :epoch-history {:depth -1}) is a no-op"
    (rf/configure :epoch-history {:depth 7})
    (rf/configure :epoch-history {:depth -1})
    (is (= 7 (:depth (epoch/current-config)))
        ":depth negative silently dropped")))

(deftest configure-rejects-invalid-trace-events-keep
  (testing "(rf/configure :epoch-history {:trace-events-keep <bad>}) is a no-op"
    (rf/configure :epoch-history {:trace-events-keep 3})
    (is (= 3 (:trace-events-keep (epoch/current-config))))

    (rf/configure :epoch-history {:trace-events-keep nil})
    (is (= 3 (:trace-events-keep (epoch/current-config)))
        ":trace-events-keep nil silently dropped")

    (rf/configure :epoch-history {:trace-events-keep "no"})
    (is (= 3 (:trace-events-keep (epoch/current-config)))
        ":trace-events-keep non-numeric silently dropped")

    (rf/configure :epoch-history {:trace-events-keep -5})
    (is (= 3 (:trace-events-keep (epoch/current-config)))
        ":trace-events-keep negative silently dropped")))

(deftest configure-accepts-zero
  (testing "depth 0 and :trace-events-keep 0 are non-negative integers
            and must be accepted (0 has well-defined meaning — depth 0
            disables recording; :trace-events-keep 0 drops every
            record's :trace-events)"
    (rf/configure :epoch-history {:depth 0})
    (is (= 0 (:depth (epoch/current-config))))

    (rf/configure :epoch-history {:trace-events-keep 0})
    (is (= 0 (:trace-events-keep (epoch/current-config))))))

(deftest configure-partial-update-rejects-bad-key-only
  (testing "a configure call carrying one valid and one invalid key
            applies the valid one and drops the invalid one — failure
            in one key never poisons another"
    (rf/configure :epoch-history {:depth 7 :trace-events-keep 4})
    (rf/configure :epoch-history {:depth 11 :trace-events-keep nil})
    (let [cfg (epoch/current-config)]
      (is (= 11 (:depth cfg))
          "the valid :depth update was applied")
      (is (= 4 (:trace-events-keep cfg))
          "the invalid :trace-events-keep update was dropped"))))

;; ---- rf2-douii: ring-eviction interaction with restore -------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: ring-buffer eviction and
;; restore preconditions were each covered in isolation but never
;; together. A restore against an epoch-id that the ring has since evicted
;; must deterministically fail as :rf.epoch/restore-unknown-epoch with the
;; current (post-eviction) history-size in its tags, and must leave app-db
;; unchanged.

(deftest restore-after-eviction-fails-as-unknown-epoch
  (testing "an epoch-id that was evicted by ring-depth-cap restores as
            :rf.epoch/restore-unknown-epoch (app-db unchanged; failure
            tags carry the current history-size, which equals depth)"
    (rf/configure :epoch-history {:depth 3})
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    ;; Capture the epoch-id of the FIRST cascade before later cascades
    ;; evict it. The ring depth is 3, so dispatching 4 more :inc events
    ;; pushes the head out.
    (let [evicted-id (-> (rf/epoch-history :test/main) first :epoch-id)]
      (dotimes [_ 4] (rf/dispatch-sync [:inc] {:frame :test/main}))

      (let [history-after (rf/epoch-history :test/main)
            pre-restore   (rf/get-frame-db :test/main)
            recorded      (record-trace!)
            ok?           (rf/restore-epoch :test/main evicted-id)]
        (is (= 3 (count history-after))
            "ring still capped at depth 3")
        (is (not-any? #(= evicted-id (:epoch-id %)) history-after)
            "the captured epoch-id is no longer in history")
        (is (false? ok?) "restore rejected — epoch evicted")
        (is (= pre-restore (rf/get-frame-db :test/main))
            "app-db unchanged across the rejected restore")

        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-unknown-epoch
                                  (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-unknown-epoch fired")
          (is (= :test/main      (:frame (:tags ev))))
          (is (= evicted-id      (:epoch-id (:tags ev))))
          (is (= 3 (:history-size (:tags ev)))
              "history-size tag reflects the post-eviction size, not
               the pre-eviction count"))))))

;; ---- rf2-douii: depth 0 still fires listeners ----------------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: `configure!`'s docstring
;; documents that depth 0 'disables recording (assembled records can
;; still fire on listeners but nothing lands in the ring buffer)'. The
;; pre-existing `depth-zero-disables-recording` test covers only the
;; ring side; this test pins the listener-fanout half of the contract.

(deftest depth-zero-still-fires-listeners
  (testing "depth 0 disables the ring buffer but the assembled record
            still fans out to registered listeners"
    (rf/configure :epoch-history {:depth 0})
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (let [seen (atom [])]
      (rf/register-epoch-cb! ::watcher (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})

      (is (= [] (rf/epoch-history :test/main))
          "ring buffer is empty at depth 0")
      (is (= 2 (count @seen))
          "listener still received an assembled record per drain-settle")
      (is (= [:seed :inc] (mapv :event-id @seen))
          "records carry the per-cascade trigger event-id")
      (is (every? #(contains? % :db-after) @seen)
          "records carry the post-settle db-after")
      (is (every? #(contains? % :sub-runs) @seen))
      (is (every? #(contains? % :renders)  @seen))
      (is (every? #(contains? % :effects)  @seen)))))

;; ---- rf2-douii: rejected restore/reset paths do not mutate history /
;; ---- do not notify listeners --------------------------------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: the rejection tests
;; (`restore-failure-*` / `reset-frame-db!-failure-*`) verify the trace
;; emission and app-db stability but do NOT explicitly pin the related
;; bookkeeping contracts:
;;
;;   1. A rejected restore does not append a new record to history.
;;   2. A rejected restore does not fire registered epoch listeners.
;;   3. A rejected reset-frame-db! does not append a new record.
;;   4. A rejected reset-frame-db! does not fire registered listeners.
;;
;; A regression that swapped emission-on-failure for fanout-on-failure
;; (or appended a synthetic failure record) would slip through the
;; existing suite. Pin both halves explicitly.

(deftest rejected-restore-does-not-touch-history-or-listeners
  (testing "a rejected restore-epoch (unknown-epoch, the simplest
            rejection path) leaves the history vector untouched and
            does not fire registered listeners"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history-before (rf/epoch-history :test/main)
          seen           (atom [])]
      (rf/register-epoch-cb! ::watcher (fn [r] (swap! seen conj r)))
      (is (false? (rf/restore-epoch :test/main :no-such-epoch))
          "restore rejected")

      (is (= history-before (rf/epoch-history :test/main))
          "history vector unchanged across the rejected restore")
      (is (= [] @seen)
          "no listener fanout for the rejected restore"))))

(deftest rejected-reset-frame-db-does-not-touch-history-or-listeners
  (testing "a rejected reset-frame-db! (during-drain rejection — the
            simplest rejection path that exercises the reset surface)
            leaves history untouched and does not fire listeners"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [history-before (rf/epoch-history :test/main)
          seen           (atom [])
          attempt        (atom nil)]
      (rf/register-epoch-cb! ::watcher (fn [r] (swap! seen conj r)))
      ;; A handler that calls reset-frame-db! synchronously during a
      ;; drain — the during-drain precondition fails. The reset itself
      ;; must not fan out, but the surrounding drain still settles
      ;; normally (which appends ONE record — the one for :try-reset).
      (rf/reg-event-db :try-reset
        (fn [db _]
          (reset! attempt (rf/reset-frame-db! :test/main {:n 999}))
          db))
      (rf/dispatch-sync [:try-reset] {:frame :test/main})

      (is (false? @attempt) "reset rejected")
      (let [history-after (rf/epoch-history :test/main)
            new-records   (drop (count history-before) history-after)]
        (is (= 1 (count new-records))
            "exactly one new record — the drain settle for :try-reset
             itself; no synthetic record from the rejected reset")
        (is (= :try-reset (:event-id (first new-records)))
            "the new record's event-id is :try-reset (not
             :rf.epoch/db-replaced — the synthetic event-id the
             reset surface would have used on success)"))

      (is (= 1 (count @seen))
          "listener fired exactly once — for the :try-reset cascade
           settle, NOT for the rejected reset")
      (is (= :try-reset (:event-id (first @seen)))
          "the lone listener invocation is for the outer cascade, not
           a synthetic reset-rejection record"))))
