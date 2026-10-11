(ns re-frame.actor-handler-resolution-test
  "A message to a spawned actor runs under its TYPE's registered handler. The
  resolver reads the type's registrar entry and builds no handler of its own,
  so the validation, ensure index and region cache behind every message are
  the ones `reg-machine` made once for the type. The handler-meta around that
  handler carries the machine stamp and nothing else of the registration: a
  type's opts `:schema` pins the TYPE id as the event head, which an actor's
  messages never carry."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.registration :as rf.machines.lifecycle-fx.registration]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- counter-child [step]
  {:initial :live
   :data    {:n 0}
   :actions {:bump (fn [{data :data}] {:data (update data :n + step)})}
   :states  {:live {:on {:bump {:action :bump}}}}})

(defn- spawning-parent [child-type]
  {:initial :idle
   :data    {}
   :states  {:idle {:on {:go {:action (fn [_]
                                        {:fx [[:rf.machine/spawn {:machine-id child-type
                                                                  :id-prefix  child-type}]]})}}}}})

(defn- n-of [actor-id]
  (get-in (rf.machines.test-support/snapshot actor-id) [:data :n]))

(deftest messages-to-a-spawned-actor-build-no-handler
  (rf/reg-machine :ahr1/child  (counter-child 1))
  (rf/reg-machine :ahr1/parent (spawning-parent :ahr1/child))
  (rf/dispatch-sync [:ahr1/parent [:go]])
  (let [calls                (atom {:install-region-cache 0 :make-machine-handler 0})
        install-region-cache rf.machines.parallel/install-region-cache
        make-machine-handler rf.machines.lifecycle-fx.registration/make-machine-handler]
    (with-redefs [rf.machines.parallel/install-region-cache
                  (fn [m] (swap! calls update :install-region-cache inc) (install-region-cache m))
                  rf.machines.lifecycle-fx.registration/make-machine-handler
                  (fn [m] (swap! calls update :make-machine-handler inc) (make-machine-handler m))]
      (dotimes [_ 5] (rf/dispatch-sync [:ahr1/child#1 [:bump]])))
    (is (= 5 (n-of :ahr1/child#1)) "every message reached the actor")
    (is (= {:install-region-cache 0 :make-machine-handler 0} @calls)
        "the type's registered handler served all five messages; none was built per message")))

(deftest a-re-registered-type-serves-its-live-actors-next-message
  (rf/reg-machine :ahr2/child  (counter-child 1))
  (rf/reg-machine :ahr2/parent (spawning-parent :ahr2/child))
  (rf/dispatch-sync [:ahr2/parent [:go]])
  (rf/dispatch-sync [:ahr2/child#1 [:bump]])
  (rf/reg-machine :ahr2/child (counter-child 10))
  (rf/dispatch-sync [:ahr2/child#1 [:bump]])
  (is (= 11 (n-of :ahr2/child#1))
      "the first message ran the old definition, the next one the re-registered one"))

(deftest an-actor-runs-without-its-types-registration-opts
  (rf/reg-machine :ahr3/child
    {:schema [:tuple [:= :ahr3/child] [:tuple [:= :bump]]]}
    (counter-child 1))
  (rf/reg-machine :ahr3/parent (spawning-parent :ahr3/child))
  (rf/dispatch-sync [:ahr3/parent [:go]])
  (rf/dispatch-sync [:ahr3/child#1 [:bump]])
  (is (= 1 (n-of :ahr3/child#1))
      "the type's :schema, whose head is the type id, does not refuse the actor's message"))
