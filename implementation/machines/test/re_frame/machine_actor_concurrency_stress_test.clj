(ns re-frame.machine-actor-concurrency-stress-test
  "Eight threads, each owning its own frame, run spawn -> dispatch -> destroy
  cycles in parallel: every dispatched `:tick` runs its action exactly once, and
  each frame ends with no leaked actor, no `:spawned` slot, and a spawn counter
  equal to its cycle count. JVM-only: CLJS is single-threaded."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.util.concurrent CountDownLatch]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Cycles per thread; lower RF2_1GPX8_STRESS_ITERS for a quick pass.
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_1GPX8_STRESS_ITERS") Long/parseLong)
      5000))

(def ^:private n-threads 8)

(deftest ^:stress actor-spawn-dispatch-destroy-stress
  (let [threads (vec (for [i (range n-threads)]
                       ;; Per-thread machine ids: `reg-machine` is global, and each
                       ;; worker's action closes over its own thread's counter.
                       {:frame-id   (keyword "gpx8.stress" (str "f" i))
                        :worker-mid (keyword "gpx8.stress" (str "worker-f" i))
                        :driver-mid (keyword "gpx8.stress" (str "driver-f" i))
                        :counter    (atom 0)}))]
    (doseq [{:keys [frame-id worker-mid driver-mid counter]} threads]
      (rf/make-frame {:id frame-id})
      (rf/reg-machine worker-mid
        {:initial :running
         :data    {}
         :states  {:running {:on {:tick {:action (fn [{data :data}] (swap! counter inc) data)}}}}})
      (rf/reg-machine driver-mid
        {:initial :idle
         :states  {:idle    {:on {:go :working}}
                   :working {:spawn {:machine-id worker-mid}
                             :on    {:done :idle}}}}))
    (let [latch   (CountDownLatch. 1)
          futures (mapv (fn [{:keys [frame-id driver-mid]}]
                          (future
                            (.await latch)
                            (dotimes [_ stress-iters]
                              (rf/dispatch-sync [driver-mid [:go]] {:frame frame-id})
                              (when-let [actor-id (get-in (rf.machines.test-support/runtime-db frame-id)
                                                          [:rf.runtime/machines :spawned driver-mid [:working]])]
                                (rf/dispatch-sync [actor-id [:tick]] {:frame frame-id}))
                              (rf/dispatch-sync [driver-mid [:done]] {:frame frame-id}))))
                        threads)]
      (.countDown latch)
      (is (not-any? #{::timeout} (mapv #(deref % 120000 ::timeout) futures))
          "every thread finished within 120s")
      (is (= (repeat n-threads stress-iters) (mapv (comp deref :counter) threads))
          "each thread's :tick action ran exactly once per cycle: none dropped, none doubled")
      (is (= (repeat n-threads [[] false stress-iters])
             (mapv (fn [{:keys [frame-id worker-mid driver-mid]}]
                     (let [machines (get-in (rf.machines.test-support/runtime-db frame-id)
                                            [:rf.runtime/machines])]
                       [(filterv #(and (= (namespace worker-mid) (namespace %)) (not= driver-mid %))
                                 (keys (:snapshots machines)))
                        (contains? machines :spawned)
                        (get-in machines [:snapshots driver-mid :rf/spawn-counter worker-mid])]))
                   threads))
          "per frame: no leaked worker, the :spawned slot pruned, one allocation per cycle"))))
