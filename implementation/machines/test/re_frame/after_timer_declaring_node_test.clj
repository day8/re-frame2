(ns re-frame.after-timer-declaring-node-test
  "Every `:rf.machine.timer/*` row names the node that declared its timer:
  `:decl-path` is that node's path, and inside a parallel region `:region`
  names the region and the path is region-relative (Spec 009 §`:rf.machine.timer/*`).
  The leaf `:state` alone cannot tell two regions' same-named leaves apart,
  and each region owns its own timer for the same `:after` key."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- region [extra-on]
  {:initial :off
   :states  {:off  {:on {:go :idle}}
             :idle {:after {250 :done} :on extra-on}
             :done {}}})

(def ^:private two-regions
  {:type    :parallel
   :regions {:left  (region {:leave :off})
             :right (region {})}})

(defn- rows [captured op]
  (->> captured
       (filter #(= op (:operation %)))
       (mapv #(select-keys (:tags %) [:state :decl-path :region]))))

(deftest region-timer-rows-name-their-region
  (rf/reg-machine :decl-node/regions two-regions)
  (rf.machines.test-support/with-trace-capture captured
    (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
      (rf/dispatch-sync [:decl-node/regions [:go]]))
    (let [epoch (into {} (keep (fn [ev]
                                 (when (= :rf.machine.timer/scheduled (:operation ev))
                                   [(get-in ev [:tags :region]) (get-in ev [:tags :epoch])])))
                      @captured)]
      (is (= #{{:state :idle :decl-path [:idle] :region :left}
               {:state :idle :decl-path [:idle] :region :right}}
             (set (rows @captured :rf.machine.timer/scheduled))))
      (rf/dispatch-sync [:decl-node/regions [:leave]])
      (is (= [{:state :idle :decl-path [:idle] :region :left}]
             (rows @captured :rf.machine.timer/cancelled)))
      (rf/dispatch-sync [:decl-node/regions [:rf.machine.timer/after-elapsed
                                             250 (:left epoch) [:left :idle]]])
      (is (= [{:state :idle :decl-path [:idle] :region :left}]
             (rows @captured :rf.machine.timer/stale-after)))
      (rf/dispatch-sync [:decl-node/regions [:rf.machine.timer/after-elapsed
                                             250 (:right epoch) [:right :idle]]])
      (is (= [{:state :idle :decl-path [:idle] :region :right}]
             (rows @captured :rf.machine.timer/fired))))))

(deftest a-server-region-skip-names-its-region
  (let [machine (-> (rf.machines.parallel/install-region-cache two-regions)
                    (assoc :rf/platform :server :rf/frame :decl-node/server
                           :rf/parent-id :decl-node/server-actor))
        snap    (rf.machines.parallel/build-initial-snapshot machine {:bootstrap-pending? false})]
    (rf.machines.test-support/with-trace-capture captured
      (rf.machines.parallel/machine-transition machine snap [:go])
      (is (= #{{:state :idle :decl-path [:idle] :region :left}
               {:state :idle :decl-path [:idle] :region :right}}
             (set (rows @captured :rf.machine.timer/skipped-on-server)))))))

(deftest a-compound-timer-row-carries-its-full-path-and-no-region
  (rf/reg-machine :decl-node/compound
                  {:initial :idle
                   :states  {:idle  {:on {:go :outer}}
                             :outer {:initial :inner
                                     :states  {:inner {:after {250 :done}}
                                               :done  {}}}}})
  (rf.machines.test-support/with-trace-capture captured
    (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
      (rf/dispatch-sync [:decl-node/compound [:go]]))
    (is (= [{:state :inner :decl-path [:outer :inner]}]
           (rows @captured :rf.machine.timer/scheduled)))))
