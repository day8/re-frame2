(ns re-frame.destroy-closed-grammar-cljs-test
  "The `:rf.machine/destroy` argument grammar is CLOSED: a keyword actor id, the
  tracked map `{:rf/parent-id p :rf/invoke-id i}`, or the `:spawn-all` map
  `{:rf/spawn-all true :rf/parent-id p :rf/invoke-id i}`. Anything else emits
  exactly one `:rf.error/machine-destroy-bad-arg` and mutates nothing.

  Presence of `:rf/spawn-all`, not its truthiness, selects the `:spawn-all`
  shape: routed by truthiness or by its coordinates alone, a malformed carrier
  would reach the tracked branch, read the whole join-state map as an actor id,
  clear the join slot and orphan the live children."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   ;; loads the machines artefact, so this ns runs in isolation
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- reg-join-parent!
  "Register and start a two-child `:spawn-all` parent over inert children, with
  `:abort` leaving the join state. Returns the seeded join state."
  [parent-id child-id]
  (rf/reg-machine child-id {:initial :running :states {:running {}}})
  (rf/reg-machine parent-id
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all {:children        [{:id :a :machine-id child-id}
                                                      {:id :b :machine-id child-id}]
                                    :join            :all
                                    :on-all-complete [:all/done]}
                        :on        {:abort :idle}}}})
  (rf/dispatch-sync [parent-id [:start]])
  (join-state parent-id))

(defn- destroy-with! [args]
  (rf/reg-event ::fire-destroy (fn [_ [_ a]] {:fx [[:rf.machine/destroy a]]}))
  (rf/dispatch-sync [::fire-destroy args]))

(deftest malformed-destroy-args-fail-closed
  (let [pre      (reg-join-parent! :dcg/p :dcg/child)
        children (vals (:children pre))]
    (is (= 2 (count children)))
    (doseq [[cause arg] [[:unknown-shape {:rf/reap false :rf/parent-id :dcg/p :rf/invoke-id [:racing] :rf/child-id :a}]
                         [:unknown-shape {:rf/reap true :rf/spawn-all true :rf/parent-id :dcg/p
                                          :rf/invoke-id [:racing] :rf/child-id :a}]
                         [:unknown-shape {:rf/spawn-all false :rf/parent-id :dcg/p :rf/invoke-id [:racing]}]
                         [:slot-shape-mismatch {:rf/parent-id :dcg/p :rf/invoke-id [:racing]}]
                         [:unknown-shape "not-an-actor-id"]]]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! arg)
      (is (= [[cause] pre true []]
             [(mapv (comp :cause :tags) (rf.machines.test-support/events-of :rf.error/machine-destroy-bad-arg))
              (join-state :dcg/p)
              (every? some? (map rf.machines.test-support/snapshot children))
              (rf.machines.test-support/events-of :rf.machine/destroyed)])
          (pr-str arg)))))

(deftest genuine-spawn-all-exit-stays-green
  (let [children (vals (:children (reg-join-parent! :dcg/sa-parent :dcg/sa-child)))]
    (rf/dispatch-sync [:dcg/sa-parent [:abort]])
    (is (= [nil [nil nil] []]
           [(join-state :dcg/sa-parent)
            (mapv rf.machines.test-support/snapshot children)
            (rf.machines.test-support/events-of :rf.error/machine-destroy-bad-arg)]))))
