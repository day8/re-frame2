(ns re-frame.epoch-drain-serialization-test
  "Tool-Pair state writes (`restore-epoch!` / `replace-frame-state!`) serialize
  against the frame's event drain on the core `:drain-lock` (Spec 002 §Single
  drainer per frame), so a tool write holds ONE serial position relative to any
  event transition.

  Each test forces the TOCTOU window open: the write validates its
  preconditions with no drain in flight, THEN a concurrent `dispatch-sync`
  reads db and blocks mid-transition holding `:drain-lock`, THEN the write is
  released. An unserialized write would splice into the transition and be
  overwritten while still returning `true`. JVM-only — CLJS cannot preempt."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Loaded so the fixture's ns-load registrar baseline matches the
            ;; rest of the epoch suite.
            [re-frame.machines])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private join-timeout-ms 20000)

(defn- await-future [f]
  (deref f join-timeout-ms ::timeout))

(defn- await-latch [^CountDownLatch latch]
  (.await latch (long join-timeout-ms) TimeUnit/MILLISECONDS))

(defn- await-promise [p]
  (deref p join-timeout-ms ::timeout))

(defn- target-epoch-id
  "The epoch-id of the recorded epoch whose `:db-after` equals `db`."
  [frame-id db]
  (some (fn [r] (when (= db (:db-after r)) (:epoch-id r)))
        (rf/epoch-history frame-id)))

(deftest restore-epoch!-serialized-against-concurrent-drain
  (testing "a restore that races an event which read db first serializes after
            that event, so its installed value is the durable final state"
    (let [frame-id        :drainlin/restore
          precond-passed  (promise)
          handler-read    (promise)
          release-precond (CountDownLatch. 1)
          release-handler (CountDownLatch. 1)
          barrier-armed?  (atom true)]
      (rf/make-frame {:id frame-id})
      (rf/reg-event :set (fn [_ [_ v]] {:db {:n v}}))
      ;; Reads db, then blocks mid-transition holding :drain-lock, then commits n+1.
      (rf/reg-event :blocked-inc
        (fn [{:keys [db]} _]
          (deliver handler-read db)
          (await-latch release-handler)
          {:db {:n (inc (:n db))}}))
      (rf/dispatch-sync [:set 1] {:frame frame-id})
      (rf/dispatch-sync [:set 2] {:frame frame-id})
      (let [eid-1      (target-epoch-id frame-id {:n 1})
            orig-check rf.epoch.tool-pair/check-restore-preconditions!]
        (with-redefs
          [rf.epoch.tool-pair/check-restore-preconditions!
           (fn [& args]
             (let [result (apply orig-check args)]
               (when (compare-and-set! barrier-armed? true false)
                 (deliver precond-passed result)
                 (await-latch release-precond))
               result))]
          (let [restore-fut (future (rf/restore-epoch! frame-id eid-1))]
            (await-promise precond-passed)
            (let [drain-fut (future (rf/dispatch-sync [:blocked-inc] {:frame frame-id}))]
              (await-promise handler-read)
              (.countDown release-precond)
              ;; A beat for the restore to park on :drain-lock (or, unserialized, land).
              (Thread/sleep 100)
              (.countDown release-handler)
              (let [restore-result (await-future restore-fut)]
                (await-future drain-fut)
                (is (true? restore-result) "restore reported success")
                ;; The event read {:n 2} and commits {:n 3}; only the restore
                ;; committing LAST yields {:n 1}. An unserialized write leaves {:n 3}.
                (is (= {:n 1} (rf/app-db-value frame-id))
                    "the restore's installed value is durable — serialized after the event")))))))))

(deftest replace-frame-state!-serialized-preserves-omitted-partition
  (testing "an app-db-only replace racing an event that changes the OMITTED
            runtime-db partition installs the coherent whole frame-state, and
            its synthetic epoch records exactly that state"
    (let [frame-id        :drainlin/replace
          precond-passed  (promise)
          runtime-read    (promise)
          release-precond (CountDownLatch. 1)
          release-handler (CountDownLatch. 1)
          barrier-armed?  (atom true)]
      (rf/make-frame {:id frame-id})
      (rf/reg-event :seed
        (fn [_ _]
          {:db            {:app :v0}
           :rf.db/runtime {:rf.runtime/marker :r0}}))
      ;; Changes ONLY the runtime-db partition, blocking mid-transition.
      (rf/reg-event :bump-runtime
        (fn [{rt :rf.db/runtime} _]
          (deliver runtime-read rt)
          (await-latch release-handler)
          {:rf.db/runtime {:rf.runtime/marker :r1}}))
      (rf/dispatch-sync [:seed] {:frame frame-id})
      (let [orig-check     rf.epoch.tool-pair/check-replace-frame-state-preconditions!
            history-before (count (rf/epoch-history frame-id))]
        (with-redefs
          [rf.epoch.tool-pair/check-replace-frame-state-preconditions!
           (fn [f fs]
             (let [result (orig-check f fs)]
               (when (compare-and-set! barrier-armed? true false)
                 (deliver precond-passed result)
                 (await-latch release-precond))
               result))]
          (let [replace-fut (future (rf/replace-frame-state! frame-id {:rf.db/app {:app :patched}}))]
            (await-promise precond-passed)
            (let [drain-fut (future (rf/dispatch-sync [:bump-runtime] {:frame frame-id}))]
              (await-promise runtime-read)
              (.countDown release-precond)
              (Thread/sleep 100)
              (.countDown release-handler)
              (let [replace-result (await-future replace-fut)]
                (await-future drain-fut)
                (is (true? replace-result) "replace reported success")
                (let [installed (rf/frame-state-value frame-id)
                      history   (rf/epoch-history frame-id)
                      synthetic (first (filter #(= :rf.epoch/db-replaced (:event-id %)) history))]
                  (is (= :r1 (:rf.runtime/marker (:rf.db/runtime installed)))
                      "the concurrent event's runtime-db update survived the omitted-partition carry-forward")
                  (is (= {:app :patched} (:rf.db/app installed))
                      "the app-db partition holds the tool patch")
                  ;; An unserialized write would record runtime :r0 here while the
                  ;; frame holds :r1, so restoring the anchor would revert the event.
                  (is (= installed (:frame-state-after synthetic))
                      "the synthetic epoch's :frame-state-after equals the installed frame-state")
                  (is (= (+ history-before 2) (count history))
                      "history grew by exactly the drain epoch + one synthetic replace epoch"))))))))))
