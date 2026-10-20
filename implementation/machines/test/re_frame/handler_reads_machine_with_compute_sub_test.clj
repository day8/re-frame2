(ns re-frame.handler-reads-machine-with-compute-sub-test
  "An event handler reads a machine's snapshot by computing the `[:rf/machine
  id]` vector a view subscribes to against its own `:rf.db/runtime`
  coeffect, so it never spells where the framework stores the snapshot."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest a-handler-reads-a-machine-snapshot-with-compute-sub
  (rf/reg-machine :door/m {:initial :closed
                           :states  {:closed {:on {:open {:target :open}}}
                                     :open   {}}})
  (rf/dispatch-sync [:door/m [:open]])
  (let [seen (atom ::unset)]
    (rf/reg-event :test/read-door
      (fn [{rt :rf.db/runtime} _]
        (reset! seen [(rf/compute-sub [:rf/machine :door/m] rt)
                      (rf/compute-sub [:rf.machine/has-tag? :door/m :busy] rt)])
        {}))
    (rf/dispatch-sync [:test/read-door])
    (is (= [(get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                    [:rf.runtime/machines :snapshots :door/m])
            false]
           @seen)
        "the vector computes the snapshot the runtime-db holds")
    (is (= :open (:state (first @seen))))))
