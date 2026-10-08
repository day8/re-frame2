(ns re-frame.parallel-region-runtime-overlay-test
  "`region-machine` caches each region's synthetic spec at registration; every
  region step overlays the LIVE parent's `:rf/platform` / `:rf/frame` /
  `:rf/cofx` onto it, so region logic never runs against stale cached values."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(defn- transition [machine event]
  (rf.machines.parallel/machine-transition
    machine (rf.machines.parallel/build-initial-snapshot machine {:bootstrap-pending? false}) event))

(deftest server-region-after-skips-host-timer-despite-stale-cache
  ;; The cache is primed from the unstamped machine (nil platform / frame).
  (let [unstamped (rf.machines.parallel/install-region-cache
                    {:type    :parallel
                     :data    {}
                     :regions {:climate {:initial :idle
                                         :states  {:idle    {:on {:start :cooling}}
                                                   :cooling {:after {5000 :idle}}}}
                               :lights  {:initial :off :states {:off {}}}}})
        _      (rf.machines.parallel/region-machine unstamped :climate)
        server (assoc unstamped :rf/platform :server :rf/frame :test/server-frame
                      :rf/parent-id :overlay/server)
        traces (rf.machines.test-support/with-trace-capture seen
                 (transition server [:start])
                 @seen)
        timer  (fn [op] (->> traces (filter #(= op (:operation %)))
                             (mapv (juxt (comp :frame :tags) (comp :platform :tags)))))]
    (is (= {:skipped [[:test/server-frame :server]] :scheduled []}
           {:skipped   (timer :rf.machine.timer/skipped-on-server)
            :scheduled (timer :rf.machine.timer/scheduled)}))))

(deftest region-cofx-does-not-leak-from-cache-into-later-transition
  ;; Prime the cache from a parent carrying `:rf/cofx`; a later transition whose
  ;; parent carries none must surface no `:rf.cofx` on the region action ctx.
  (let [seen      (atom [])
        installed (rf.machines.parallel/install-region-cache
                    {:type    :parallel
                     :data    {}
                     :actions {:capture (fn [{cofx :rf.cofx :as ctx}]
                                          (swap! seen conj {:has? (contains? ctx :rf.cofx) :cofx cofx})
                                          {:data (:data ctx)})}
                     :regions {:a {:initial :one :states {:one {:on {:go {:action :capture}}}}}
                               :b {:initial :rest :states {:rest {}}}}})]
    (rf.machines.parallel/region-machine (assoc installed :rf/cofx {:rf/time-ms 1}) :a)
    (transition installed [:go])
    (is (= [{:has? false :cofx nil}] @seen))))
