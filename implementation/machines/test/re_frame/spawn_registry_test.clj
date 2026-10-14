(ns re-frame.spawn-registry-test
  "A declarative spawn is tracked at `[:rf.runtime/machines :spawned <parent-id>
  <invoke-id>]` and mirrored in the parent's `[:data :rf/spawned <invoke-id>]`,
  one entry per invoke; the destroy cascade finds the child through the slot,
  and both views clear when the child ends."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private frame-db rf.machines.test-support/runtime-db)

(deftest multi-child-independent-tracking
  (doseq [id [:child/a :child/b :gc/x :gc/y]]
    (rf/reg-machine id {:initial :running :data {} :states {:running {}}}))
  (rf/reg-machine :sup/many
    {:initial :idle
     :states  {:idle      {:on {:fork-a :a-running :fork-b :b-running :fork-all :forking}}
               :a-running {:spawn {:machine-id :child/a} :on {:back :idle}}
               :b-running {:spawn {:machine-id :child/b} :on {:back :idle}}
               :forking   {:spawn-all {:children        [{:id :x :machine-id :gc/x}
                                                         {:id :y :machine-id :gc/y}]
                                       :join            :all
                                       :on-all-complete [:all/done]}
                           :on        {:back :idle :all/done :idle}}}})
  (let [slots  #(get-in (frame-db) [:rf.runtime/machines :spawned :sup/many])
        mirror #(:data (snapshot :sup/many))]
    (rf/dispatch-sync [:sup/many [:fork-a]])
    (is (= [{[:a-running] :child/a#1} {:rf/spawned {[:a-running] :child/a#1}}]
           [(slots) (mirror)])
        "the slot and the parent's own :data record the child under its invoke-id — nothing else")
    (rf/dispatch-sync [:sup/many [:back]])
    (rf/dispatch-sync [:sup/many [:fork-b]])
    (is (nil? (snapshot :child/a#1)) "leaving :a-running destroyed A through its slot")
    (is (= [{[:b-running] :child/b#1} {:rf/spawned {[:b-running] :child/b#1}}]
           [(slots) (mirror)])
        "A's entries cleared with A; B's are keyed independently")
    (rf/dispatch-sync [:sup/many [:back]])
    (rf/dispatch-sync [:sup/many [:fork-all]])
    (is (= {:rf/spawned {[:forking] {:x :gc/x#1 :y :gc/y#1}}} (mirror))
        ":spawn-all mirrors its whole children map under the one invoke-id")
    (rf/dispatch-sync [:sup/many [:back]])
    (is (not (contains? (:rf.runtime/machines (frame-db)) :spawned))
        "with every invoke torn down, the :spawned root is pruned")))
