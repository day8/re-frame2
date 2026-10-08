(ns re-frame.initial-snapshot-unification-test
  "A spawned actor's initial snapshot carries its spec's `:meta` (Spec 005
  §Snapshot shape), so version checks see the `:meta` the spec declares."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest spawned-actor-snapshot-carries-meta
  (rf/reg-machine :worker/proc {:initial :running
                                :meta    {:schema-version 7 :user-tag :probe}
                                :states  {:running {}}})
  (rf/reg-machine :sup/main {:initial :idle
                             :states  {:idle    {:on {:start :working}}
                                       :working {:spawn {:machine-id :worker/proc}}}})
  (rf/dispatch-sync [:sup/main [:start]])
  (let [child-id (get-in (rf.machines.test-support/machine-data :sup/main) [:rf/spawned [:working]])]
    (is (= {:schema-version 7 :user-tag :probe}
           (:meta (rf.machines.test-support/snapshot child-id))))))
