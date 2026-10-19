(ns re-frame.runtime-slot-test
  "A snapshot is `{:state :data :tags? :meta?}` plus ONE framework-owned root
  map, `:rf/runtime`, holding the runtime's bookkeeping: the spawned actor's
  type and lineage, the spawn and `:after` counters, and the ids of the actors
  it spawned. `:data` is the machine's own memory and nothing else, so a
  `[:schemas :data]` schema describes exactly what the author wrote. Callbacks
  read lineage and spawned ids from their context map, never from `:data`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- rf-keys
  "The `:rf/*` keys of map `m`, as a set."
  [m]
  (set (filter #(= "rf" (namespace %)) (keys m))))

(deftest a-machine-with-no-bookkeeping-carries-no-runtime-map
  (rf/reg-machine :rs/plain
    {:initial :idle
     :data    {:n 0}
     :states  {:idle {:on {:bump {:action (fn [{:keys [data]}] {:data (update data :n inc)})}}}}})
  (rf/dispatch-sync [:rs/plain [:bump]])
  (is (= {:state :idle :data {:n 1}} (snapshot :rs/plain))
      "nothing to keep, so the snapshot is exactly the user-facing {:state :data}"))

(deftest spawned-lineage-and-counters-live-in-runtime-not-data
  (let [seen (atom {})]
    (rf/reg-machine :rs/child
      {:initial :running
       :data    {:n 1}
       :states  {:running {:entry (fn [{:keys [self-id parent-id]}]
                                    (swap! seen assoc :child [self-id parent-id])
                                    nil)}}})
    (rf/reg-machine :rs/parent
      {:initial :idle
       :data    {:k :v}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :rs/child}
                           :on    {:peek {:action (fn [{:keys [spawned]}]
                                                    (swap! seen assoc :parent spawned)
                                                    nil)}}}}})
    (rf/dispatch-sync [:rs/parent [:go]])
    (rf/dispatch-sync [:rs/parent [:peek]])
    (let [parent (snapshot :rs/parent)
          child  (snapshot :rs/child#1)]
      (is (= {:k :v} (:data parent)) "the parent's :data is its own memory alone")
      (is (= {:n 1} (:data child)) "the child's :data is its own memory alone")
      (is (= #{:rf/runtime} (rf-keys parent) (rf-keys child))
          "every framework slot sits under the one root :rf/runtime map")
      (is (= {:spawn-counter  {:rs/child 1}
              :spawn-attempts {[:working] 1}
              :spawned        {[:working] :rs/child#1}}
             (:rf/runtime parent)))
      (is (= {:machine-type    :rs/child
              :self-id         :rs/child#1
              :parent-id       :rs/parent
              :invoke-id       [:working]
              :invoke-attempt  1
              :work-generation 1}
             (:rf/runtime child)))
      (is (= {:child  [:rs/child#1 :rs/parent]
              :parent {[:working] :rs/child#1}}
             @seen)
          "callbacks read their lineage and spawned ids from the context map"))))

(deftest after-epochs-live-in-runtime
  (rf/reg-machine :rs/timed
    {:initial :waiting
     :data    {}
     :states  {:waiting {:after {60000 :expired}}
               :expired {}}})
  (rf/dispatch-sync [:rs/timed [:rf.machine/start]])
  (let [snap (snapshot :rs/timed)]
    (is (= {} (:data snap)))
    (is (= {[:waiting] 1} (get-in snap [:rf/runtime :after-epoch]))))
  (rf/reg-machine :rs/timed-regions
    {:type    :parallel
     :data    {}
     :regions {:left {:initial :waiting
                      :states  {:waiting {:after {60000 :expired}}
                                :expired {}}}
               :right {:initial :idle
                       :states  {:idle {}}}}})
  (rf/dispatch-sync [:rs/timed-regions [:rf.machine/start]])
  (let [snap (snapshot :rs/timed-regions)]
    (is (= {} (:data snap)))
    (is (= {:left {[:waiting] 1}} (get-in snap [:rf/runtime :after-epoch-by-region])))))
