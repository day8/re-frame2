(ns re-frame.final-state-spawn-order-forget-cljs-test
  "The `:final?` auto-destroy (`finalize-machine`) forgets the finished actor
  from its frame's `spawn-order` channel, as the explicit destroy does."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.spawn-order :as rf.machines.spawn-order]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(deftest final-state-forgets-actor-from-spawn-order
  (rf/make-frame {:id :fsf1/scratch})
  (rf/reg-machine :fsf1/child
    {:initial :running
     :data    {}
     :states  {:running {:on {:fin :done}}
               :done    {:final? true}}})
  (rf/reg-machine :fsf1/parent
    {:initial :working
     :states  {:working {:spawn {:machine-id :fsf1/child}}}})
  (rf/dispatch-sync [:fsf1/parent [:rf.machine.spawn/spawned]] {:frame :fsf1/scratch})
  (let [spawned-id (get-in (rf.machines.test-support/runtime-db :fsf1/scratch)
                           [:rf.runtime/machines :spawned :fsf1/parent [:working]])]
    (is (some #{spawned-id} (rf.machines.spawn-order/frame-order :fsf1/scratch))
        "the live spawned child is recorded in its frame's spawn-order")
    (rf/dispatch-sync [spawned-id [:fin]] {:frame :fsf1/scratch})
    (is (not-any? #{spawned-id} (rf.machines.spawn-order/frame-order :fsf1/scratch))
        "the finished child was forgotten from its frame's spawn-order")))
