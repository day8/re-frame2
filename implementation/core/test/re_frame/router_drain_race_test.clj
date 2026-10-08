(ns re-frame.router-drain-race-test
  "The single-drainer invariant under real JVM threads (Spec 002
  §Run-to-completion): an executor-thread async drain racing a main-thread
  `dispatch-sync` drain, and many submitter threads racing one frame's
  drain, never drop or double-process an envelope. Both run on the real
  `interop/next-tick` executor, because the race window exists only there;
  a drainer that admitted two threads fails at roughly 4 per 1000
  iterations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  ;; The ambient scope the stress loop's bare dispatches resolve against.
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; Well above the per-1000 failure rate of an unlocked drainer, and under
;; ~60s on the JVM.
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_YNK7_STRESS_ITERS") Long/parseLong)
      5000))

(deftest single-drainer-invariant-stress
  ;; The race: the executor's drain of the async envelope and the main
  ;; thread's sync drain both peek one queue. Unlocked, it shows as
  ;; [:sync-only :sync-only] — one envelope run twice, another dropped.
  (testing (str "no envelope dropped or run twice across " stress-iters " iterations")
    (let [failures      (atom [])
          ;; Per-iteration state behind one indirection, so the handlers are
          ;; registered once rather than racing leftover envelopes each iteration.
          current-order (atom (atom []))
          done-promise  (atom (promise))]
      (rf/reg-event :outside-async-stress
        (fn [{:keys [db]} _]
          (swap! @current-order conj :outside-async)
          (deliver @done-promise :ok)
          {:db (assoc db :outside? true)}))
      (rf/reg-event :sync-only-stress
        (fn [{:keys [db]} _]
          (swap! @current-order conj :sync-only)
          {:db (assoc db :sync? true)}))
      (dotimes [i stress-iters]
        (let [order (atom [])
              done  (promise)]
          (reset! current-order order)
          (reset! done-promise done)
          (rf/dispatch [:outside-async-stress])
          (rf/dispatch-sync [:sync-only-stress])
          (when (= :timeout (deref done 5000 :timeout))
            (swap! failures conj {:iter i :reason :timeout :order @order}))
          (let [final-order @order
                sync-count  (count (filter #{:sync-only} final-order))
                async-count (count (filter #{:outside-async} final-order))]
            (when-not (and (= 1 sync-count) (= 1 async-count))
              (swap! failures conj {:iter        i
                                    :order       final-order
                                    :sync-count  sync-count
                                    :async-count async-count})))))
      (is (zero? (count @failures))
          (str "Expected zero failures across " stress-iters
               " iterations; got " (count @failures)
               (when (pos? (count @failures))
                 (str ". First few: " (pr-str (vec (take 5 @failures))))))))))

(deftest concurrent-dispatch-stress
  ;; Many submitter threads dispatching to one frame while the main thread
  ;; drains synchronously: every event runs exactly once, including those
  ;; enqueued in the window between a drainer's empty-check and its release.
  (testing "N submitter threads + sync drain — no events lost or duplicated"
    (let [n-submitters 8
          per-thread   200
          total        (* n-submitters per-thread)]
      ;; A :drain-depth high enough that the default 100 never halts the cascade.
      (rf/make-frame {:id :stress.race/main :drain-depth (* 4 (+ total 2))})
      (rf/reg-event :bump
        (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (let [latch   (java.util.concurrent.CountDownLatch. 1)
            futures (vec (for [_ (range n-submitters)]
                           (future
                             (.await latch)
                             (dotimes [_ per-thread]
                               (rf/dispatch [:bump] {:frame :stress.race/main})))))]
        (.countDown latch)
        (rf/dispatch-sync [:bump] {:frame :stress.race/main})
        (doseq [f futures] @f)
        ;; A final sync drain waits for the lock, then drains whatever is queued.
        (rf/dispatch-sync [:bump] {:frame :stress.race/main}))
      (let [expected (+ total 2)
            actual   (:n (rf/app-db-value :stress.race/main))]
        (is (= expected actual)
            (str "Expected " expected " events processed (no drops/dups); "
                 "got " actual))))))
