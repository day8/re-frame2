(ns re-frame.hicasso.reaper-coalescing-cljs-test
  "THE REAPERS ARM ONE TIMER PER HORIZON PER TURN — not one per cell and
  one per entry (rf2-6c12m.18).

  A cell whose last reader leaves and a read-set entry nobody has claimed
  are each given a horizon of grace before they are dropped — one
  macrotask for a cell, `entry-reap-horizon-ms` for an entry. The runtime
  used to arm one `setTimeout` per cell and per entry, so a cold mount of
  N distinct-read boundaries armed N timers during the render and
  unmounting them armed 2N more: on the 300-row shape the runtime is tuned
  for, hundreds of heap allocations and scheduler entries per turn for
  work that is one drain.

  ## The row is the 300-row shape, taken at the commit seam

  Three hundred boundaries, each reading its own key, mounted through
  `commit-boundary!` — the same `subscribe` closure `useSyncExternalStore`
  calls — and released through the cleanups it hands back. No React, no
  DOM: the timers under measurement are the collector's own, and the seam
  is where they are armed.

  Timers are counted at the platform's own door. `setTimeout` is wrapped
  for the window of one step and every arm passes THROUGH to the real
  scheduler, so the count is a count and the schedule is unchanged — the
  residue readings that follow are taken past the runtime's own horizon
  exactly as every other suite takes them.

  Measured before the coalescing: 300 timers to mount, 600 to unmount.
  After: one and two."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-id ::reaper-coalescing)

(def ^:private rows 300)

(rf/reg-event :reap/seed (fn [_ [_ n]] {:db {:rows (vec (range n))}}))
(rf/reg-sub   :reap/row  (fn [db [_ i]] (get-in db [:rows i])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(defn- counting-timers
  "Run `f` with every `setTimeout` counted and passed through to the real
  scheduler. Answers the count."
  [f]
  (let [g    js/globalThis
        real (.-setTimeout g)
        !n   (volatile! 0)]
    (set! (.-setTimeout g)
          (fn [& args]
            (vswap! !n inc)
            (.apply real g (to-array args))))
    (try (f) (finally (set! (.-setTimeout g) real)))
    @!n))

(defn- mount-rows!
  "React's place at the commit seam for `rows` boundaries, each reading
  its own key. Answers the release fns."
  []
  (mapv (fn [i]
          (rf.hicasso.impl.collector/render-body frame-id (fn [_] (rf.hicasso.impl.collector/sub [:reap/row i])) {})
          (rf.hicasso.impl.collector/commit-boundary! (rf.hicasso.impl.collector/last-reads) (fn [])))
        (range rows)))

(deftest a-cold-mount-of-300-distinct-read-boundaries-arms-one-timer-per-horizon
  (async done
    (rf/make-frame {:id frame-id})
    (rf/with-frame frame-id (rf/dispatch-sync [:reap/seed rows]))
    (let [!releases (volatile! nil)
          armed     (counting-timers #(vreset! !releases (mount-rows!)))]
      (is (= 1 armed)
          (str "timers armed by mounting " rows " rows: " armed))
      (.then (rf.hicasso.test.runtime/quiesced!)
             (fn [_]
               (testing "past the horizon, the claimed entries and the held
                         cells are exactly what a mount retains"
                 (is (= {:cells rows :cell-refs rows :boundaries rows
                         :edges rows :entries rows}
                        (rf.hicasso.test.runtime/residue))))
               (let [armed (counting-timers #(doseq [release @!releases] (release)))]
                 (is (= 2 armed)
                     (str "timers armed by unmounting " rows " rows: " armed)))
               (.then (rf.hicasso.test.runtime/quiesced!)
                      (fn [_]
                        (testing "and past the horizon every cell, membership
                                  and entry has been reaped — the coalescing
                                  changed how many timers, never what runs"
                          (is (= {:cells 0 :cell-refs 0 :boundaries 0
                                  :edges 0 :entries 0}
                                 (rf.hicasso.test.runtime/residue))))
                        (done))))))))
