(ns re-frame.machine-definition-survives-teardown-cljs-test
  "A `reg-machine` DEFINITION survives actor teardown: the registration is the
  load-time program that makes an address creatable, the snapshot is the
  instance (Spec 005 §Liveness is derived from runtime-db). One entry is both
  a singleton's address and the TYPE every spawn resolves through, so a
  teardown that cleared it would fail every later spawn of the type."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(deftest frame-destroy-preserves-the-definition-for-both-actor-kinds
  ;; Frame teardown reaps the singleton down the straggler branch and both
  ;; spawned actors down the full teardown, one of them sitting at its own
  ;; TYPE's keyword. Each runs :exit once, and both addresses still create.
  (let [exits (atom [])]
    (rf/reg-machine :xjee/fd-type
      {:initial :running
       :data    {}
       :states  {:running {:exit (fn [_] (swap! exits conj :singleton) {})}}})
    (rf/reg-machine :xjee/fd-spawned
      {:initial :running
       :data    {}
       :states  {:running {:exit (fn [{data :data}]
                                   (swap! exits conj (:rf/self-id data))
                                   {})}}})
    (rf/reg-event :xjee/fd-spawn-pair
      (fn [_ _]
        {:fx [[:rf.machine/spawn {:machine-id     :xjee/fd-spawned
                                  :fixed-actor-id :xjee/fd-spawned}]
              [:rf.machine/spawn {:machine-id     :xjee/fd-spawned
                                  :fixed-actor-id :xjee/fd-sibling}]]}))
    (rf/make-frame {:id :xjee/fd-frame :doc "frame-destroy coverage"})
    (rf/dispatch-sync [:xjee/fd-type [:kick]] {:frame :xjee/fd-frame})
    (rf/dispatch-sync [:xjee/fd-spawn-pair] {:frame :xjee/fd-frame})
    (rf/destroy-frame! :xjee/fd-frame)
    (rf/dispatch-sync [:xjee/fd-type [:kick]])
    (rf/dispatch-sync [:xjee/fd-spawn-pair])
    (is (= [{:singleton 1 :xjee/fd-spawned 1 :xjee/fd-sibling 1} true true]
           [(frequencies @exits)
            (some? (snapshot :xjee/fd-type))
            (some? (snapshot :xjee/fd-spawned))]))))
