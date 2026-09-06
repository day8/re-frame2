(ns re-frame.epoch-drain-serialization-test
  "Deterministic JVM regression for rf2-3fc89f.4 — Tool-Pair state writes
  (`restore-epoch!` / `replace-frame-state!`) MUST be serialized against the
  frame's event drain via the core single-drainer `:drain-lock`, so a tool
  write holds ONE serial position relative to any event transition.

  The defect (pre-fix): the precondition validators read the router's
  `:in-drain?` / `:in-sync-drain?` flags, but the coherent read / reconcile /
  physical write / success bookkeeping ran OUTSIDE the frame's `:drain-lock`.
  A drain that started AFTER validation and BEFORE the write could interleave
  between a handler's `db` read and its commit, so the tool write spliced into
  the middle of an event transition — a non-linearizable result — while the
  tool op still returned `true` and emitted success telemetry.

  These tests force that TOCTOU window open with a barrier wrapped around the
  precondition validator: the restore/replace validates its preconditions with
  NO drain in flight (so validation passes), THEN a concurrent `dispatch-sync`
  starts a drain and blocks its handler mid-transition (holding `:drain-lock`),
  THEN the tool write is released. On the buggy code the write splices in and
  is overwritten; with the fix the write blocks on `:drain-lock` and serializes
  AFTER the drain, producing a linearizable transition whose installed value
  matches the recorded synthetic epoch.

  CLJS cannot thread-preempt, so this interleaving is JVM-only; the reentrant
  mid-drain refusal (which CLJS DOES need) is covered on both runtimes by the
  during-drain precondition tests. Per Spec 002 §Single drainer per frame and
  the frame `:drain-lock` discipline (`re-frame.frame/call-serialized-with-drain!`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; machines is pulled in for parity with the rest of the epoch
            ;; suite so the ns-load registrar snapshot the fixture restores is
            ;; the same shape (the reconcile hooks stay nil-safe here).
            [re-frame.machines])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- orchestration helpers -------------------------------------------------

(def ^:private join-timeout-ms 20000)

(defn- await-future [f]
  (deref f join-timeout-ms ::timeout))

(defn- await-latch [^CountDownLatch latch]
  (.await latch (long join-timeout-ms) TimeUnit/MILLISECONDS))

(defn- await-promise [p]
  (deref p join-timeout-ms ::timeout))

(defn- target-epoch-id
  "The epoch-id of the recorded epoch whose app-db projection equals `db`."
  [frame-id db]
  (some (fn [r] (when (= db (:db-after r)) (:epoch-id r)))
        (rf/epoch-history frame-id)))

;; ---- Acceptance criterion 1 — restore-epoch! linearizability ---------------

(deftest restore-epoch!-serialized-against-concurrent-drain
  (testing "A restore whose preconditions pass with no drain in flight, then
            races a concurrent event that reads db before the restore lands,
            must NOT splice a mid-transition write: the outcome is a
            linearizable schedule (the restore serializes after the event and
            its installed value is durable), not a success that was silently
            overwritten by the event commit."
    (let [frame-id :drainlin/restore]
      (rf/make-frame {:id frame-id :doc "restore drain-serialization frame"})
      (rf/reg-event :set (fn [{:keys [db]} [_ v]] {:db {:n v}}))

      ;; Barrier plumbing.
      (let [precond-passed (promise)         ;; R -> main: check computed (:ok?)
            handler-read   (promise)         ;; D -> main: handler read db
            release-precond (CountDownLatch. 1) ;; main -> R: return from check
            release-handler (CountDownLatch. 1) ;; main -> D: finish handler
            barrier-armed?  (atom true)
            handler-read-db (atom nil)]

        ;; The racing event: reads db, publishes it, then blocks mid-transition
        ;; holding the frame's :drain-lock until released, then commits n+1.
        (rf/reg-event :blocked-inc
          (fn [{:keys [db]} _]
            (reset! handler-read-db db)
            (deliver handler-read db)
            (await-latch release-handler)
            {:db {:n (inc (:n db))}}))

        ;; Seed: record an epoch at {:n 1}; move current state to {:n 2}.
        (rf/dispatch-sync [:set 1] {:frame frame-id})
        (rf/dispatch-sync [:set 2] {:frame frame-id})
        (let [eid-1 (target-epoch-id frame-id {:n 1})
              orig-check rf.epoch.tool-pair/check-restore-preconditions!]
          (is (some? eid-1) "seeded an epoch whose db-after is {:n 1}")
          (is (= {:n 2} (rf/app-db-value frame-id)) "current db is {:n 2}")

          (with-redefs
            [rf.epoch.tool-pair/check-restore-preconditions!
             (fn [f e]
               (let [result (orig-check f e)]
                 (when (compare-and-set! barrier-armed? true false)
                   ;; Preconditions were resolved with NO drain in flight.
                   (deliver precond-passed result)
                   ;; Park until the concurrent drain is mid-transition.
                   (await-latch release-precond))
                 result))]

            (let [restore-fut (future (rf/restore-epoch! frame-id eid-1))]
              ;; 1. Restore validates preconditions (drain not yet in flight).
              (let [pc (await-promise precond-passed)]
                (is (= :ok (:outcome pc))
                    "restore preconditions passed BEFORE any drain — the TOCTOU window is real"))
              ;; 2. Start the racing drain; wait until it has read db and is
              ;;    blocked mid-transition holding :drain-lock.
              (let [drain-fut (future (rf/dispatch-sync [:blocked-inc] {:frame frame-id}))]
                (is (= {:n 2} (await-promise handler-read))
                    "the racing event read the pre-restore db {:n 2}")
                ;; 3. Release the restore to attempt its write. On the buggy
                ;;    code it writes {:n 1} immediately (no lock); with the fix
                ;;    it blocks on :drain-lock.
                (.countDown release-precond)
                ;; Give the restore a beat to either finish (buggy) or park on
                ;; the lock (fixed).
                (Thread/sleep 100)
                ;; 4. Let the blocked handler commit {:n 3} and settle.
                (.countDown release-handler)

                (let [restore-result (await-future restore-fut)
                      drain-result   (await-future drain-fut)]
                  (is (not= ::timeout restore-result) "restore future completed (no deadlock)")
                  (is (not= ::timeout drain-result) "drain future completed (no deadlock)")

                  (is (true? restore-result) "restore reported success")
                  ;; The event read the pre-restore state {:n 2}, so a
                  ;; linearizable schedule places the event BEFORE the restore;
                  ;; therefore the restore committed LAST and its value must be
                  ;; the durable final state. The bug produces {:n 3} — the
                  ;; restore's write erased by the event commit despite the true
                  ;; return — which no serial order can produce.
                  (is (= {:n 1} (rf/app-db-value frame-id))
                      (str "restore reported success but its installed value must be "
                           "durable; final db was "
                           (pr-str (rf/app-db-value frame-id))
                           " (a mid-transition splice overwritten by the event "
                           "commit is non-linearizable)")))))))))))

;; ---- Acceptance criterion 2 — replace-frame-state! partition coherence -----

(deftest replace-frame-state!-serialized-preserves-omitted-partition
  (testing "A partial replace-frame-state! that patches ONLY app-db, racing a
            concurrent event that changes the OMITTED runtime-db partition,
            must install the coherent whole frame-state AND record a synthetic
            epoch whose :frame-state-after equals the installed value — no
            partition update lost, no synthetic anchor describing a state that
            was never a serial frame state."
    (let [frame-id :drainlin/replace]
      (rf/make-frame {:id frame-id :doc "replace drain-serialization frame"})
      (rf/reg-event :seed
        (fn [_ _]
          {:db            {:app :v0}
           :rf.db/runtime {:rf.runtime/marker :r0}}))

      (let [precond-passed  (promise)
            runtime-read    (promise)
            release-precond (CountDownLatch. 1)
            release-handler (CountDownLatch. 1)
            barrier-armed?  (atom true)]

        ;; Racing event: changes ONLY the runtime-db partition (the partition
        ;; the tool patch omits), blocking mid-transition holding :drain-lock.
        (rf/reg-event :bump-runtime
          (fn [{rt :rf.db/runtime} _]
            (deliver runtime-read rt)
            (await-latch release-handler)
            {:rf.db/runtime {:rf.runtime/marker :r1}}))

        (rf/dispatch-sync [:seed] {:frame frame-id})
        (is (= :r0 (:rf.runtime/marker (:rf.db/runtime (rf/frame-state-value frame-id))))
            "seeded runtime-db marker :r0")

        (let [orig-check rf.epoch.tool-pair/check-replace-frame-state-preconditions!
              history-before (count (rf/epoch-history frame-id))]
          (with-redefs
            [rf.epoch.tool-pair/check-replace-frame-state-preconditions!
             (fn [f fs]
               (let [result (orig-check f fs)]
                 (when (compare-and-set! barrier-armed? true false)
                   (deliver precond-passed result)
                   (await-latch release-precond))
                 result))]

            ;; The tool patch touches ONLY app-db; runtime-db is omitted and
            ;; must be carried forward from whatever the frame holds at write
            ;; time (which — after serialization — is the event's :r1).
            (let [replace-fut (future (rf/replace-frame-state! frame-id {:rf.db/app {:app :patched}}))]
              (let [pc (await-promise precond-passed)]
                (is (= :ok (:outcome pc))
                    "replace preconditions passed BEFORE any drain"))
              (let [drain-fut (future (rf/dispatch-sync [:bump-runtime] {:frame frame-id}))]
                (is (= :r0 (:rf.runtime/marker (await-promise runtime-read)))
                    "the racing event read the pre-replace runtime-db :r0")
                (.countDown release-precond)
                (Thread/sleep 100)
                (.countDown release-handler)

                (let [replace-result (await-future replace-fut)
                      drain-result   (await-future drain-fut)]
                  (is (not= ::timeout replace-result) "replace future completed (no deadlock)")
                  (is (not= ::timeout drain-result) "drain future completed (no deadlock)")
                  (is (true? replace-result) "replace reported success")

                  (let [installed  (rf/frame-state-value frame-id)
                        history    (rf/epoch-history frame-id)
                        synthetics (filter #(= :rf.epoch/db-replaced (:event-id %)) history)
                        synthetic  (first synthetics)]
                    ;; The event's runtime update must NOT be lost: the omitted
                    ;; partition carries forward the serialized-before event's :r1.
                    (is (= :r1 (:rf.runtime/marker (:rf.db/runtime installed)))
                        (str "the concurrent event's runtime-db update must survive the "
                             "omitted-partition carry-forward; installed runtime-db was "
                             (pr-str (:rf.db/runtime installed))))
                    (is (= {:app :patched} (:rf.db/app installed))
                        "the app-db partition holds the tool patch")
                    (is (= 1 (count synthetics))
                        "exactly one synthetic :rf.epoch/db-replaced record was appended")
                    ;; The synthetic undo-anchor must describe the EXACT installed
                    ;; transition — its :frame-state-after equals the installed
                    ;; whole frame-state. The bug records runtime :r0 here
                    ;; (computed from a stale pre-event read) while the frame holds
                    ;; :r1, so restoring the anchor would silently revert the
                    ;; event's runtime update.
                    (is (= installed (:frame-state-after synthetic))
                        (str "the synthetic epoch's :frame-state-after must equal the "
                             "installed whole frame-state; synthetic recorded "
                             (pr-str (:frame-state-after synthetic))
                             " vs installed " (pr-str installed)))
                    ;; history-before was counted after the seed; the race adds
                    ;; exactly the drain's :bump-runtime epoch + the one synthetic.
                    (is (= (+ history-before 2) (count history))
                        "history grew by exactly the drain epoch + the synthetic replace epoch")))))))))))

;; ---- Acceptance criterion 3 — reentrant mid-drain refusal, no deadlock -----

(deftest reentrant-tool-writes-refuse-mid-drain-without-deadlock
  (testing "A restore/replace invoked reentrantly from the active drainer (i.e.
            issued from inside an event handler) must return false, emit the
            documented :rf.epoch/restore-during-drain / :rf.epoch/replace-during-drain
            trace, create NO synthetic record, and NOT deadlock re-taking the
            drain lock. This is the same-thread reentrancy contract the fix must
            preserve on CLJ (and, single-threaded, CLJS)."
    (let [frame-id :drainlin/reentrant]
      (rf/make-frame {:id frame-id :doc "reentrant refusal frame"})
      (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
      (rf/dispatch-sync [:seed] {:frame frame-id})
      (let [target-eid (:epoch-id (last (rf/epoch-history frame-id)))
            restore-r  (atom ::unset)
            replace-r  (atom ::unset)
            recorded   (atom [])
            hist-before (count (rf/epoch-history frame-id))]
        (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
        ;; A handler that, mid-transition, attempts BOTH tool writes.
        (rf/reg-event :try-writes
          (fn [{:keys [db]} _]
            (reset! restore-r (rf/restore-epoch! frame-id target-eid))
            (reset! replace-r (rf/replace-frame-state! frame-id {:rf.db/app {:n 99}}))
            {:db (assoc db :phase :done)}))
        ;; If either reentrant call self-deadlocked on :drain-lock this
        ;; dispatch-sync would never return (the suite's global test timeout
        ;; would surface it); a clean settle is itself part of the assertion.
        (rf/dispatch-sync [:try-writes] {:frame frame-id})

        (is (false? @restore-r) "reentrant restore-epoch! returned false")
        (is (false? @replace-r) "reentrant replace-frame-state! returned false")
        (rf/unregister-listener! :trace ::rec)
        (let [ops (set (map :operation @recorded))]
          (is (contains? ops :rf.epoch/restore-during-drain)
              ":rf.epoch/restore-during-drain fired")
          (is (contains? ops :rf.epoch/replace-during-drain)
              ":rf.epoch/replace-during-drain fired"))
        ;; The reentrant writes created NO synthetic epoch — only the
        ;; :try-writes cascade's own settle record should have landed.
        (is (nil? (some (fn [r] (when (= :rf.epoch/db-replaced (:event-id r)) r))
                        (rf/epoch-history frame-id)))
            "no synthetic :rf.epoch/db-replaced record was created by the refused replace")
        (is (= (inc hist-before) (count (rf/epoch-history frame-id)))
            "exactly one record (the :try-writes settle) was appended — no tool-write record")
        (is (= :done (:phase (rf/app-db-value frame-id)))
            "the drain settled cleanly (no deadlock); app-db carries the handler's own commit")))))
